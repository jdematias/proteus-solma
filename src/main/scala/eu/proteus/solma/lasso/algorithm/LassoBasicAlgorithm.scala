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

package eu.proteus.solma.lasso.algorithm

import java.text.SimpleDateFormat
import java.util.Calendar

import breeze.linalg._
import breeze.numerics.{abs, sqrt}
import org.apache.flink.ml.math.Breeze._
import eu.proteus.solma.lasso.Lasso.{LassoModel, LassoParam, OptionLabeledVector}
import eu.proteus.solma.lasso.LassoStreamEvent.LassoStreamEvent

/**
  * Basic Lasso algorithm
  *
  */
object LassoBasicAlgorithm {
  def buildLasso(): LassoBasicAlgorithm = new LassoBasicAlgorithmImpl()

  def toOptionLabeledVector(event: LassoStreamEvent): OptionLabeledVector = {
    event match {
      case Left(ev) => Right((ev.pos, ev.data.asBreeze))
      case Right(ev) => Right(((ev.label, ev.poses.head), ev.data.asBreeze))
    }
  }
}

/**
  *
  * @param aggressiveness set the aggressiveness level of the algorithm. Denoted by C in paper.
  */
abstract class LassoBasicAlgorithm(protected val aggressiveness: Double)
  extends LassoAlgorithm[OptionLabeledVector, LassoParam, ((Long, Double), Double), LassoModel] with Serializable {

  override def delta(dataPoint: OptionLabeledVector,
                     model: LassoModel,
                     label: Double, lastPrediction: Double): Iterable[(Int, LassoParam)] = {

    val x_t: DenseVector[Double] = dataPoint match {
      case Left((vec, _)) => vec._2.toDenseVector
      case Right(vec) => vec._2.toDenseVector
    }
    val coilId: Long = dataPoint match {
      case Left((vec, _)) => vec._1._1
      case Right(vec) => vec._1._1
    }
    val xPosition: Double = dataPoint match {
      case Left((vec, _)) => vec._1._2
      case Right(vec) => vec._1._2
    }
    val lambda = model._3

    val a_t: DenseMatrix[Double] = x_t.asDenseMatrix.t * x_t.asDenseMatrix

    //val A_t: DenseMatrix[Double] = model._1 + a_t + inv(diag(DenseVector.fill(model._1.rows){sqrt(abs(lambda))}))
    val A_t: DenseMatrix[Double] = model._1 + a_t + lambda * pinv(diag(DenseVector.fill(model._1.rows){lastPrediction}))

    //val newLabel = model._2.asDenseMatrix * pinv(A_t) * x_t.asDenseMatrix.t

    val l_t: DenseVector[Double] = model._2 + label * x_t

    val now = Calendar.getInstance().getTime
    val timeFormat = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss")
    val currentTimeAsString = timeFormat.format(now)

    println(currentTimeAsString + " - " + "Train - " + coilId + "," + xPosition + "," + label)

    Array((0, (A_t, l_t, lambda))).toIterable
  }

  /**
    * Predict label based on the actual model
    *
    * @param dataPoint denoted by x_t in paper.
    * @param model     the corresponding model vector for the data. Denoted by w_t in paper.
    *                  The active keyset of the model vector should equal to the keyset of the data.
    * @return
    */
  override def predict(dataPoint: OptionLabeledVector, model: LassoModel): ((Long, Double), Double) = {

    val x_t: DenseVector[Double] = dataPoint match {
      case Left((vec, _)) => vec._2.toDenseVector
      case Right(vec) => vec._2.toDenseVector
    }

    val id: (Long, Double) = dataPoint match {
      case Left((vec, _)) => vec._1
      case Right(vec) => vec._1
    }

    val A_t = model._1
    val b_t = model._2

    val y_t = b_t.toDenseMatrix * pinv(A_t) * x_t

    val now = Calendar.getInstance().getTime
    val timeFormat = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss")
    val currentTimeAsString = timeFormat.format(now)

    println(currentTimeAsString + " - " + "Predict - " + id._1 + "," + id._2 + "," + y_t.data(0))

    (id, y_t.data(0))
  }

}

class LassoBasicAlgorithmImpl extends LassoBasicAlgorithm(0)
