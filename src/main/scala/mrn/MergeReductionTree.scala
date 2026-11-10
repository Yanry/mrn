import spinal.core._
import spinal.lib._

/**
  * FIFO front-end plus an explicitly generated balanced merge tree.
  *
  * laneDone is the end-of-batch indication for each lane. It is required to
  * distinguish a temporarily empty lane from a lane that will never produce
  * another element; without that distinction, globally ordered streaming
  * merge is impossible when lanes have independent arrival times.
  *
  * A generic reduceBalancedTree is intentionally not used here: it is useful
  * for combinational reductions, but a merge node needs per-input state and
  * independent Stream backpressure to retain the larger coordinate.
  */
case class MergeReductionTree(
    laneCount: Int,
    fifoDepth: Int,
    rowWidth: Int,
    colWidth: Int
) extends Component {
  require(laneCount >= 1, "laneCount must be positive")
  require(fifoDepth >= 1, "fifoDepth must be positive")
  require(rowWidth >= 1, "rowWidth must be positive")
  require(colWidth >= 1, "colWidth must be positive")

  val elementType = SparseElement(rowWidth, colWidth)
  val io = new Bundle {
    val push = Vec(slave(Stream(elementType)), laneCount)
    val laneDone = Vec(in Bool(), laneCount)
    val output = master(Stream(elementType))
    val busy = out Bool()
    val done = out Bool()
  }

  val fifos = Array.tabulate(laneCount) { lane =>
    val fifo = StreamFifo(elementType, fifoDepth)
    fifo.io.push << io.push(lane)
    fifo
  }

  var levelStreams: Seq[Stream[SparseElement]] = fifos.map(_.io.pop)
  var levelDone: Seq[Bool] = fifos.indices.map { lane =>
    io.laneDone(lane) && (fifos(lane).io.occupancy === 0)
  }
  val nodes = scala.collection.mutable.ArrayBuffer[MergeNode]()

  // Pair adjacent streams at each level. An unmatched stream is carried to
  // the next level, which preserves the balanced-tree shape for odd counts.
  while (levelStreams.size > 1) {
    val nextLevel = scala.collection.mutable.ArrayBuffer[Stream[SparseElement]]()
    val nextDone = scala.collection.mutable.ArrayBuffer[Bool]()
    var index = 0
    while (index < levelStreams.size) {
      if (index + 1 < levelStreams.size) {
        val node = MergeNode(rowWidth, colWidth)
        node.io.a << levelStreams(index)
        node.io.b << levelStreams(index + 1)
        node.io.aDone := levelDone(index)
        node.io.bDone := levelDone(index + 1)
        nextLevel += node.io.output
        nextDone += node.io.done
        nodes += node
      } else {
        nextLevel += levelStreams(index)
        nextDone += levelDone(index)
      }
      index += 2
    }
    levelStreams = nextLevel.toSeq
    levelDone = nextDone.toSeq
  }

  io.output << levelStreams.head

  val anyInputActivity = io.push.map(_.valid).orR
  val anyFifoActivity = fifos.map(_.io.pop.valid).fold(False)(_ || _)
  val anyNodeActivity = if (nodes.isEmpty) False else nodes.map(_.io.busy).fold(False)(_ || _)
  io.busy := anyInputActivity || anyFifoActivity || anyNodeActivity || io.output.valid
  io.done := levelDone.head
}

object GenerateMergeReductionTree extends App {
  SpinalConfig(targetDirectory = "rtl")
    .generateVerilog(MergeReductionTree(laneCount = 16, fifoDepth = 8, rowWidth = 8, colWidth = 8))
}
