import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class MergeReductionTreeSim extends AnyFunSuite {
  private case class Item(row: Int, col: Int, value: Int)

  private val one = 0x3c00
  private val two = 0x4000
  private val three = 0x4200
  private val five = 0x4500
  private val six = 0x4600

  private def runCase(
      lanes: Seq[Seq[Item]],
      expected: Seq[Item],
      readyPattern: Int => Boolean = _ => true,
      startDelays: Seq[Int] = Seq.empty
  ): Unit = {
    val delays = if (startDelays.isEmpty) Seq.fill(lanes.size)(0) else startDelays
    require(delays.size == lanes.size)
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS")
      .addSimulatorFlag("-std=c++14")
      .compile(MergeReductionTree(lanes.size, fifoDepth = 8, rowWidth = 8, colWidth = 8)).doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      for (lane <- dut.io.push) {
        lane.valid #= false
        lane.payload.valid #= false
      }
      for (lane <- dut.io.laneDone) lane #= false
      dut.io.output.ready #= false

      val sent = Array.fill(lanes.size)(0)
      val received = scala.collection.mutable.ArrayBuffer[Item]()
      var stalledValue: Option[Item] = None
      var cycle = 0
      while (received.size < expected.size && cycle < 400) {
        for (lane <- lanes.indices) {
          val stream = dut.io.push(lane)
          dut.io.laneDone(lane) #= sent(lane) >= lanes(lane).size
          if (cycle >= delays(lane) && sent(lane) < lanes(lane).size) {
            val item = lanes(lane)(sent(lane))
            stream.valid #= true
            stream.payload.value #= item.value
            stream.payload.row #= item.row
            stream.payload.col #= item.col
            stream.payload.valid #= true
          } else {
            stream.valid #= false
            stream.payload.valid #= false
          }
        }

        val ready = readyPattern(cycle)
        dut.io.output.ready #= ready
        sleep(1)
        if (dut.io.output.valid.toBoolean && !dut.io.output.ready.toBoolean) {
          val current = Item(
            dut.io.output.payload.row.toInt,
            dut.io.output.payload.col.toInt,
            dut.io.output.payload.value.toInt
          )
          stalledValue.foreach(previous => assert(current == previous))
          stalledValue = Some(current)
        } else {
          stalledValue = None
        }

        for (lane <- lanes.indices) {
          if (dut.io.push(lane).valid.toBoolean && dut.io.push(lane).ready.toBoolean) sent(lane) += 1
        }
        if (dut.io.output.valid.toBoolean && dut.io.output.ready.toBoolean) {
          received += Item(
            dut.io.output.payload.row.toInt,
            dut.io.output.payload.col.toInt,
            dut.io.output.payload.value.toInt
          )
        }
        dut.clockDomain.waitSampling()
        cycle += 1
      }

      assert(received.toSeq == expected, s"received=$received expected=$expected")
      assert(sent.zip(lanes).forall { case (count, input) => count == input.size })

      for (_ <- 0 until 8) {
        dut.io.output.ready #= true
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.done.toBoolean, "done must wait until the tree is drained")
    }
  }

  test("merges equal and ordered coordinates") {
    runCase(
      Seq(Seq(Item(0, 1, one)), Seq(Item(0, 1, two))),
      Seq(Item(0, 1, three))
    )
  }

  test("waits for a delayed lane before bypassing") {
    runCase(
      Seq(Seq(Item(0, 1, one)), Seq(Item(0, 1, two))),
      Seq(Item(0, 1, three)),
      startDelays = Seq(0, 5)
    )
  }

  test("handles odd lane counts, empty lanes, and backpressure") {
    runCase(
      Seq(
        Seq(Item(0, 1, one), Item(0, 3, two)),
        Seq.empty,
        Seq(Item(0, 1, five), Item(2, 4, six))
      ),
      Seq(Item(0, 1, 0x4600), Item(0, 3, two), Item(2, 4, six)),
      cycle => cycle % 5 != 0
    )
  }

  test("orders lane heads independently and compares row on equal columns") {
    runCase(
      Seq(Seq(Item(0, 2, one), Item(1, 3, two)), Seq(Item(1, 2, two), Item(2, 3, one))),
      Seq(Item(0, 2, one), Item(1, 2, two), Item(1, 3, two), Item(2, 3, one))
    )
  }

  test("merges the full multi-lane example") {
    runCase(
      Seq(
        Seq(Item(0, 1, one), Item(0, 3, two)),
        Seq(Item(0, 2, three), Item(1, 3, 0x4400)),
        Seq(Item(0, 1, five), Item(2, 4, six))
      ),
      Seq(
        Item(0, 1, 0x4600),
        Item(0, 2, three),
        Item(0, 3, two),
        Item(1, 3, 0x4400),
        Item(2, 4, six)
      ),
      cycle => cycle % 4 != 0
    )
  }

  test("reduces consecutive equal coordinates across three lanes") {
    runCase(
      Seq(
        Seq(Item(0, 1, one), Item(0, 2, two)),
        Seq(Item(0, 1, three), Item(0, 2, 0x4400)),
        Seq(Item(0, 1, five), Item(0, 3, six))
      ),
      Seq(Item(0, 1, 0x4880), Item(0, 2, 0x4600), Item(0, 3, six))
    )
  }

  test("supports a five-lane tree with empty lanes") {
    runCase(
      Seq(
        Seq(Item(0, 0, one)),
        Seq.empty,
        Seq(Item(0, 1, two)),
        Seq.empty,
        Seq(Item(0, 2, three))
      ),
      Seq(Item(0, 0, one), Item(0, 1, two), Item(0, 2, three)),
      cycle => cycle % 3 != 1
    )
  }
}
