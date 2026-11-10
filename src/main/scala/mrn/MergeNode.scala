import spinal.core._
import spinal.lib._

/**
  * Stateful two-input merge node.
  *
  * Each input has a one-element holding register. This is important for merge:
  * when the coordinates differ, only the smaller input is consumed and the
  * larger input remains in its holding register for the next comparison.
  */
case class MergeNode(rowWidth: Int, colWidth: Int) extends Component {
  val elementType = SparseElement(rowWidth, colWidth)
  val io = new Bundle {
    val a = slave(Stream(elementType))
    val b = slave(Stream(elementType))
    val aDone = in Bool()
    val bDone = in Bool()
    val output = master(Stream(elementType))
    val busy = out Bool()
    val done = out Bool()
  }

  val aValue = Reg(elementType)
  val bValue = Reg(elementType)
  val aValid = Reg(Bool()) init(False)
  val bValid = Reg(Bool()) init(False)

  val outputValue = Reg(elementType)
  val outputValid = Reg(Bool()) init(False)
  val adder = FP16Add()
  val addRow = Reg(UInt(rowWidth bits))
  val addCol = Reg(UInt(colWidth bits))
  val addPending = Reg(Bool()) init(False)

  // laneDone closes the producer, but does not discard data already buffered
  // in its FIFO. Those elements still need to be accepted here.
  io.a.ready := !aValid
  io.b.ready := !bValid

  when(io.a.fire) {
    aValue.value := io.a.payload.value
    aValue.row := io.a.payload.row
    aValue.col := io.a.payload.col
    aValue.valid := True
    aValid := True
  }
  when(io.b.fire) {
    bValue.value := io.b.payload.value
    bValue.row := io.b.payload.row
    bValue.col := io.b.payload.col
    bValue.valid := True
    bValid := True
  }

  io.output.valid := outputValid
  io.output.payload := outputValue

  adder.io.a := aValue.value
  adder.io.b := bValue.value
  adder.io.out_ready := !outputValid

  val bothHeld = aValid && bValid
  val sameCoordinate = SparseElement.coordinateEqual(aValue, bValue)
  val canStartAdd = !outputValid && !addPending && bothHeld && sameCoordinate && adder.io.in_ready
  adder.io.in_valid := canStartAdd
  val adderInFire = adder.io.in_valid && adder.io.in_ready
  val adderOutFire = adder.io.out_valid && adder.io.out_ready

  when(adderInFire) {
    addRow := aValue.row
    addCol := aValue.col
    aValid := False
    bValid := False
    addPending := True
  }

  when(io.output.fire) {
    outputValid := False
  } elsewhen(adderOutFire) {
    outputValue.value := adder.io.result
    outputValue.row := addRow
    outputValue.col := addCol
    outputValue.valid := True
    outputValid := True
    addPending := False
  }

  // A non-equal comparison is resolved into the output register. The output
  // register is intentionally not bypassed, so it remains stable under stall.
  when(!outputValid && !addPending && !adderInFire && !io.output.fire) {
    when(bothHeld && !sameCoordinate) {
      when(SparseElement.coordinateLess(aValue, bValue)) {
        outputValue.value := aValue.value
        outputValue.row := aValue.row
        outputValue.col := aValue.col
        outputValue.valid := True
        outputValid := True
        aValid := False
      } otherwise {
        outputValue.value := bValue.value
        outputValue.row := bValue.row
        outputValue.col := bValue.col
        outputValue.valid := True
        outputValid := True
        bValid := False
      }
    } elsewhen(aValid && io.bDone) {
      outputValue.value := aValue.value
      outputValue.row := aValue.row
      outputValue.col := aValue.col
      outputValue.valid := True
      outputValid := True
      aValid := False
    } elsewhen(bValid && io.aDone) {
      outputValue.value := bValue.value
      outputValue.row := bValue.row
      outputValue.col := bValue.col
      outputValue.valid := True
      outputValid := True
      bValid := False
    }
  }

  io.done := io.aDone && io.bDone && !aValid && !bValid && !outputValid &&
    !addPending && !adder.io.out_valid
  io.busy := aValid || bValid || outputValid || addPending || adder.io.out_valid ||
    io.a.valid || io.b.valid
}
