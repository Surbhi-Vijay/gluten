/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.execution

import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.expression.ExpressionConverter
import io.glutenproject.extension.ValidationResult
import io.glutenproject.metrics.{MetricsUpdater, NoopMetricsUpdater}
import io.glutenproject.substrait.SubstraitContext
import io.glutenproject.substrait.rel.RelBuilder
import io.glutenproject.utils.SubstraitUtil

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight, BuildSide}
import org.apache.spark.sql.catalyst.plans.{InnerLike, JoinType}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.{SparkPlan, SQLExecution}
import org.apache.spark.sql.execution.joins.BaseJoinExec
import org.apache.spark.sql.vectorized.ColumnarBatch

import io.substrait.proto.CrossRel

abstract class BroadcastNestedLoopJoinExecTransformer(
    left: SparkPlan,
    right: SparkPlan,
    buildSide: BuildSide,
    joinType: JoinType,
    condition: Option[Expression])
  extends BaseJoinExec
  with TransformSupport {

  def joinBuildSide: BuildSide = buildSide

  override def leftKeys: Seq[Expression] = Nil
  override def rightKeys: Seq[Expression] = Nil

  private lazy val substraitJoinType: CrossRel.JoinType =
    SubstraitUtil.toCrossRelSubstrait(joinType)

  private lazy val buildTableId: String = "BuildTable-" + buildPlan.id

  // Hint substrait to switch the left and right,
  // since we assume always build right side in substrait.
  private lazy val needSwitchChildren: Boolean = buildSide match {
    case BuildLeft => true
    case BuildRight => false
  }

  protected lazy val (buildPlan, streamedPlan) = if (needSwitchChildren) {
    (left, right)
  } else {
    (right, left)
  }

  override def columnarInputRDDs: Seq[RDD[ColumnarBatch]] = {
    val streamedRDD = getColumnarInputRDDs(streamedPlan)
    val broadcastRDD = {
      val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
      BackendsApiManager.getBroadcastApiInstance
        .collectExecutionBroadcastHashTableId(executionId, buildTableId)
      createBroadcastBuildSideRDD()
    }
    // FIXME: Do we have to make build side a RDD?
    streamedRDD :+ broadcastRDD
  }

  protected def createBroadcastBuildSideRDD(): BroadcastBuildSideRDD

  // TODO: Add Metrics Updater
  override def metricsUpdater(): MetricsUpdater = NoopMetricsUpdater

  override def output: Seq[Attribute] = {
    joinType match {
      case _: InnerLike =>
        left.output ++ right.output
      case x =>
        throw new IllegalArgumentException(s"${getClass.getSimpleName} not take $x as the JoinType")
    }
  }

  override def outputPartitioning: Partitioning = buildSide match {
    case BuildLeft =>
      joinType match {
        case _: InnerLike => right.outputPartitioning
        case x =>
          throw new IllegalArgumentException(
            s"BroadcastNestedLoopJoin should not take $x as the JoinType with building left side")
      }
    case BuildRight =>
      joinType match {
        case _: InnerLike => left.outputPartitioning
        case x =>
          throw new IllegalArgumentException(
            s"BroadcastNestedLoopJoin should not take $x as the JoinType with building right side")
      }
  }

  override def doTransform(context: SubstraitContext): TransformContext = {
    val streamedPlanContext = streamedPlan.asInstanceOf[TransformSupport].doTransform(context)
    val (inputStreamedRelNode, inputStreamedOutput) =
      (streamedPlanContext.root, streamedPlanContext.outputAttributes)

    val buildPlanContext = buildPlan.asInstanceOf[TransformSupport].doTransform(context)
    val (inputBuildRelNode, inputBuildOutput) =
      (buildPlanContext.root, buildPlanContext.outputAttributes)

    val expressionNode = condition.map {
      expr =>
        ExpressionConverter
          .replaceWithExpressionTransformer(expr, inputStreamedOutput ++ inputBuildOutput)
          .doTransform(context.registeredFunction)
    }
    val extensionNode =
      JoinUtils.createExtensionNode(inputStreamedOutput ++ inputBuildOutput, validation = false)
    val operatorId = context.nextOperatorId(this.nodeName)

    val crossRel = RelBuilder.makeCrossRel(
      inputStreamedRelNode,
      inputBuildRelNode,
      substraitJoinType,
      expressionNode.orNull,
      extensionNode,
      context,
      operatorId
    )

    val projectRelPostJoinRel = JoinUtils.createProjectRelPostJoinRel(
      needSwitchChildren,
      joinType,
      inputStreamedOutput,
      inputBuildOutput,
      context,
      operatorId,
      crossRel,
      inputStreamedOutput,
      inputBuildOutput
    )

    JoinUtils.createTransformContext(
      needSwitchChildren,
      output,
      projectRelPostJoinRel,
      inputStreamedOutput,
      inputBuildOutput)
  }

  override protected def doValidateInternal(): ValidationResult = {
    if (!BackendsApiManager.getSettings.supportBroadcastNestedLoopJoinExec()) {
      return ValidationResult.notOk("Broadcast Nested Loop join is not supported in this backend")
    }
    if (substraitJoinType == CrossRel.JoinType.UNRECOGNIZED) {
      return ValidationResult.notOk(s"$joinType is not supported with Broadcast Nested Loop Join")
    }
    val substraitContext = new SubstraitContext
    val expressionNode = condition.map {
      expr =>
        ExpressionConverter
          .replaceWithExpressionTransformer(expr, streamedPlan.output ++ buildPlan.output)
          .doTransform(substraitContext.registeredFunction)
    }
    val extensionNode =
      JoinUtils.createExtensionNode(streamedPlan.output ++ buildPlan.output, validation = true)

    val currRel = RelBuilder.makeCrossRel(
      null,
      null,
      substraitJoinType,
      expressionNode.orNull,
      extensionNode,
      substraitContext,
      substraitContext.nextOperatorId(this.nodeName)
    )
    doNativeValidation(substraitContext, currRel)
  }
}