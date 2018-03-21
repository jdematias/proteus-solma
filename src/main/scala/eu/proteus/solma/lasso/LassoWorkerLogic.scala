/*
 * Copyright (C) 2017 The Proteus Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.proteus.solma.lasso

import breeze.linalg.{DenseVector => DenseBreezeVector, Vector => BreezeVector}
import org.apache.flink.ml.math.Breeze._
import eu.proteus.solma.events.StreamEventWithPos
import eu.proteus.solma.lasso.Lasso.{LassoModel, LassoParam, OptionLabeledVector}
import eu.proteus.solma.lasso.LassoStreamEvent.LassoStreamEvent
import eu.proteus.solma.lasso.algorithm.{FlatnessMappingAlgorithm, LassoAlgorithm, LassoBasicAlgorithm}
import hu.sztaki.ilab.ps.{ParameterServerClient, WorkerLogic}
import eu.proteus.solma.utils.FileUtils

import scala.collection.mutable

class LassoWorkerLogic (modelBuilder: ModelBuilder[LassoParam, LassoModel],
                        lassoMethod: LassoAlgorithm[OptionLabeledVector, LassoParam, ((Long, Double), Double),
                          LassoModel]) extends WorkerLogic[LassoStreamEvent, LassoParam, ((Long, Double), Double)]
{
  val unpredictedVecs = new mutable.Queue[LassoStreamEvent]()
  val unlabeledVecs = new mutable.HashMap[Long, mutable.Queue[StreamEventWithPos[(Long, Double)]]]()
  val labeledVecs = new mutable.Queue[OptionLabeledVector]

  override def onRecv(data: LassoStreamEvent,
                      ps: ParameterServerClient[LassoParam, ((Long, Double), Double)]): Unit = {

    data match {
      case Left(v) =>
        FileUtils.writeCutreLog("Measurement: " + v.pos._1.toString + "," + v.pos._2.toString)
        if (!unlabeledVecs.keys.exists(x => x == v.pos._1)) {
          unlabeledVecs(v.pos._1) = new mutable.Queue[StreamEventWithPos[(Long, Double)]]()
        }
        unlabeledVecs(v.pos._1).enqueue(v)
        unpredictedVecs.enqueue(data)
        FileUtils.writeCutreLog("Pull-Measurement")
        ps.pull(0)
      case Right(v) =>
        FileUtils.writeCutreLog("Flatness:" + v.label.toString)
        if (unlabeledVecs.keys.exists(x => x == v.label)) {
          val poses = unlabeledVecs(v.label).toVector.map(x => x.pos._2)
          var labels = Vector[(Double, Double)]()

          for (i <- 0 until v.labels.data.length) {
            labels = (v.poses(i), v.labels(i)) +: labels
          }

          val interpolatedLabels = new FlatnessMappingAlgorithm(poses, labels).apply

          val processedEvents: Iterable[OptionLabeledVector] = unlabeledVecs(v.label).toVector.zipWithIndex.map(
            zipped => {
              val data: BreezeVector[Double] = DenseBreezeVector.fill(76){0.0}
              data(zipped._1.slice.head) = zipped._1.data(zipped._1.slice.head)
              val vec: OptionLabeledVector = Left(((zipped._1.pos, data/*zipped._1.data.asBreeze*/),
                interpolatedLabels(zipped._2)))
              vec
            }
          )
          labeledVecs ++= processedEvents
          FileUtils.writeCutreLog("He etiquetado: " + processedEvents.size.toString + " measurements")
        }
        FileUtils.writeCutreLog("Pull-Flatness")
        ps.pull(0)
    }
  }

  override def onPullRecv(paramId: Int,
                          modelValue: LassoParam,
                          ps: ParameterServerClient[LassoParam, ((Long, Double), Double)]):Unit = {

    var model: Option[LassoModel] = None

    FileUtils.writeCutreLog("Recibo el modelo y hay " + unpredictedVecs.size + " vectores para predecir")
    FileUtils.writeCutreLog("Recibo el modelo y hay " + labeledVecs.size + " vectores para aprender")

    while (unpredictedVecs.nonEmpty) {
      val dataPoint = unpredictedVecs.dequeue()
      val prediction = lassoMethod.predict(LassoBasicAlgorithm.toOptionLabeledVector(dataPoint), modelValue)
      ps.output(prediction)
    }

    while (labeledVecs.nonEmpty) {
      val restedData = labeledVecs.dequeue()
      restedData match {
        case Left(v) => model = Some(lassoMethod.delta(restedData, modelValue, v._2).head._2)
        case Right(v) => //It must not be processed here
      }
    }
    if (model.nonEmpty) {
      FileUtils.writeCutreLog(model.get._1.toString)
      FileUtils.writeCutreLog(model.get._2.toString)
      FileUtils.writeCutreLog("Push - He actualizado el modelo")
      ps.push(0, model.get)
    }
  }
}

