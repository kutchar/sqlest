/*
 * Copyright 2014 JHC Systems Limited
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

package sqlest.extractor

import org.joda.time.DateTime
import scala.collection.immutable.{ Queue, ListMap }

sealed trait Extractor[A] {
  type Accumulator

  type SingleResult

  def extractHeadOption(row: Iterator[Row]): Option[SingleResult]
  def extractAll(row: Iterator[Row]): List[SingleResult]

  def initialize(row: Row): Accumulator
  def accumulate(accumulator: Accumulator, row: Row): Accumulator
  // An Option is emitted to represent null values
  // If a null value is not handled by an OptionExtractor then an exception will be thrown
  def emit(accumulator: Accumulator): Option[A]

  protected def checkNullValueAndGet[T](t: Option[T]) =
    t.getOrElse(throw new NullPointerException("Tried to extract a null value without an OptionExtractor"))

  def map[B](func: A => B) = MappedExtractor(this, func)
  def asOption = OptionExtractor(this)
}

sealed trait SingleExtractor[A] extends Extractor[A] {
  final type SingleResult = A

  final def extractHeadOption(rows: Iterator[Row]): Option[A] =
    if (rows.hasNext) {
      Some(checkNullValueAndGet(emit(initialize(rows.next))))
    } else None

  final def extractAll(rows: Iterator[Row]): List[A] =
    if (rows.hasNext) {
      var accumulator = Queue(checkNullValueAndGet(emit(initialize(rows.next))))

      while (rows.hasNext)
        accumulator = accumulator :+ checkNullValueAndGet(emit(initialize(rows.next)))

      accumulator.toList
    } else Nil

  def asList = ListMultiExtractor(this)
  def groupBy[B](groupBy: Extractor[B]) = GroupedMultiExtractor(this, groupBy)
}

sealed trait MultiExtractor[A] extends Extractor[List[A]] {
  type Accumulator <: Traversable[_]

  final type SingleResult = A

  final def extractHeadOption(rows: Iterator[Row]): Option[A] =
    if (rows.hasNext) {
      var accumulator = initialize(rows.next)

      while (rows.hasNext && accumulator.size == 1)
        accumulator = accumulate(accumulator, rows.next)

      checkNullValueAndGet(emit(accumulator)).headOption
    } else None

  final def extractAll(rows: Iterator[Row]): List[A] =
    if (rows.hasNext) {
      var accumulator = initialize(rows.next)

      while (rows.hasNext)
        accumulator = accumulate(accumulator, rows.next)

      checkNullValueAndGet(emit(accumulator))
    } else Nil
}

/**
 * Extractor that always returns the same value
 */
case class ConstantExtractor[A](value: A) extends SingleExtractor[A] {
  type Accumulator = A

  def initialize(row: Row) = value
  def accumulate(accumulator: A, row: Row) = value
  def emit(accumulator: A) = Some(accumulator)
}

/**
 * Extractor that emits the values for a single cell.
 */
trait CellExtractor[A] extends SingleExtractor[A] {
  type Accumulator = Option[A]

  def initialize(row: Row) = read(row)

  def accumulate(accumulator: Accumulator, row: Row) = read(row)

  def emit(accumulator: Accumulator) = accumulator

  def read(row: Row): Option[A]
}

trait ProductExtractor[A <: Product] extends SingleExtractor[A] {
  def innerExtractors: List[Extractor[_]]
}

/**
 * An extractor that behaves as `inner` but pipes its `emitted` values through `func`.
 */
case class MappedExtractor[A, B](inner: Extractor[A], func: A => B) extends SingleExtractor[B] {
  type Accumulator = inner.Accumulator

  def initialize(row: Row) = inner.initialize(row)

  def accumulate(accumulator: inner.Accumulator, row: Row) = inner.accumulate(accumulator, row)

  def emit(accumulator: inner.Accumulator) = inner.emit(accumulator).map(func)
}

/**
 * An extractor that returns `None` if all of the cells in the
 * `inner` extractor are `null` in the row.
 *
 * If any underlying cell is non-`null`, this returns `Some`
 * of `inner`'s result.
 */
case class OptionExtractor[A](inner: Extractor[A]) extends SingleExtractor[Option[A]] {
  type Accumulator = inner.Accumulator

  def initialize(row: Row) = inner.initialize(row)

  def accumulate(accumulator: inner.Accumulator, row: Row) = inner.accumulate(accumulator, row)

  def emit(accumulator: inner.Accumulator) = Some(inner.emit(accumulator))
}

/**
 * An extractor that aggregates results from a seq of extractors into a seq.
 */
case class SeqExtractor[A](extractors: Seq[Extractor[A]]) extends SingleExtractor[Seq[A]] {
  type Accumulator = Seq[Option[A]]

  def initialize(row: Row): Accumulator = extractors.map(inner => inner.emit(inner.initialize(row)))

  def accumulate(accumulator: Accumulator, row: Row) =
    accumulator ++ extractors.map(inner => inner.emit(inner.initialize(row)))

  def emit(accumulator: Accumulator) = Some(accumulator.map(checkNullValueAndGet))
}

/**
 * An extractor that accumulates results into a list.
 */
case class ListMultiExtractor[A](inner: Extractor[A]) extends MultiExtractor[A] {
  type Accumulator = Queue[Option[A]]

  def initialize(row: Row) = Queue(inner.emit(inner.initialize(row)))

  def accumulate(accumulator: Queue[Option[A]], row: Row) = accumulator :+ inner.emit(inner.initialize(row))

  // In a left join either all row are full or all rows are null.
  // These are the valid accumulators that will return a list
  def emit(accumulator: Queue[Option[A]]) = {
    val noRowsEmpty = accumulator.forall(!_.isEmpty)
    val allRowsEmpty = accumulator.forall(_.isEmpty)
    if (noRowsEmpty)
      Some(accumulator.map(_.get).toList)
    else if (allRowsEmpty)
      Some(Nil)
    else
      None
  }
}

/**
 * An extractor that accumulates results with the same groupBy value into the same value
 */
case class GroupedMultiExtractor[A, B](inner: Extractor[A], groupBy: Extractor[B]) extends MultiExtractor[A] {
  // Consider using a tuple of a Queue and a HashMap as the Accumulator for efficiency
  type Accumulator = ListMap[B, inner.Accumulator]

  def initialize(row: Row) =
    ListMap(checkNullValueAndGet(groupBy.emit(groupBy.initialize(row))) -> inner.initialize(row))

  def accumulate(accumulator: ListMap[B, inner.Accumulator], row: Row) = {
    val groupByKey = checkNullValueAndGet(groupBy.emit(groupBy.initialize(row)))

    val newInnerAccumulator = accumulator.get(groupByKey) match {
      case Some(innerAccumulator) => inner.accumulate(innerAccumulator, row)
      case None => inner.initialize(row)
    }

    accumulator + (groupByKey -> newInnerAccumulator)
  }

  def emit(accumulator: ListMap[B, inner.Accumulator]) = Some(accumulator.values.map(inner.emit).toList.map(checkNullValueAndGet))
}