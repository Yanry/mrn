import spinal.core._

/** One sparse partial sum. Coordinates are ordered by (col, row). */
case class SparseElement(rowWidth: Int, colWidth: Int) extends Bundle {
  val value = Bits(16 bits)
  val row = UInt(rowWidth bits)
  val col = UInt(colWidth bits)
  val valid = Bool()

  override def clone(): SparseElement = SparseElement(rowWidth, colWidth)
}

object SparseElement {
  def coordinateEqual(a: SparseElement, b: SparseElement): Bool =
    (a.row === b.row) && (a.col === b.col)

  /** True when a is the earlier element in the required (col, row) order. */
  def coordinateLess(a: SparseElement, b: SparseElement): Bool =
    (a.col < b.col) || ((a.col === b.col) && (a.row < b.row))
}
