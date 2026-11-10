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
        val outputValid = dut.io.output.valid.toBoolean
        val current = if (outputValid) {
          assert(dut.io.output.payload.valid.toBoolean, "valid output must have a valid payload")
          Some(Item(
            dut.io.output.payload.row.toInt,
            dut.io.output.payload.col.toInt,
            dut.io.output.payload.value.toInt
          ))
        } else {
          None
        }
        stalledValue.foreach(previous => assert(current.contains(previous), "output changed while stalled"))
        stalledValue = if (outputValid && !ready) current else None

        for (lane <- lanes.indices) {
          if (dut.io.push(lane).valid.toBoolean && dut.io.push(lane).ready.toBoolean) sent(lane) += 1
        }
        if (outputValid && ready) {
          received += current.get
          assert(received.size <= expected.size, s"unexpected output: $received")
        }
        dut.clockDomain.waitSampling()
        cycle += 1
      }

      assert(received.toSeq == expected, s"received=$received expected=$expected")
      assert(sent.zip(lanes).forall { case (count, input) => count == input.size })

      for (lane <- lanes.indices) {
        dut.io.push(lane).valid #= false
        dut.io.laneDone(lane) #= true
      }
      var drained = false
      var drainCycles = 0
      while (!drained && drainCycles < 400) {
        dut.io.output.ready #= true
        sleep(1)
        assert(!dut.io.output.valid.toBoolean, "extra output after expected sequence")
        drained = dut.io.done.toBoolean
        dut.clockDomain.waitSampling()
        drainCycles += 1
      }
      assert(drained, "done must wait until the tree is drained")
      for (_ <- 0 until 4) {
        sleep(1)
        assert(dut.io.done.toBoolean && !dut.io.output.valid.toBoolean,
          "done must remain asserted without extra output")
        dut.clockDomain.waitSampling()
      }
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

  test("reduces across all four levels of a 16-lane tree") {
    // Every lane contributes at (0,1), then has a distinct (col,row)=(2,15-lane).
    // The second heads are reverse-ordered across lanes, not sorted as a vector.
    val lanes = (0 until 16).map { lane =>
      Seq(Item(0, 1, one), Item(15 - lane, 2, two))
    }
    val expected = Seq(Item(0, 1, 0x4c00)) ++
      (0 until 16).map(row => Item(row, 2, two))
    runCase(lanes, expected, cycle => cycle % 7 < 3)
  }

  test("handles delayed and empty branches in a 16-lane tree") {
    val lanes = (0 until 16).map { lane =>
      if (lane % 2 == 0) Seq.empty[Item]
      else Seq(Item(0, 0, one), Item(15 - lane, 1, two))
    }
    val expected = Seq(Item(0, 0, 0x4800)) ++
      (0 until 16 by 2).map(row => Item(row, 1, two))
    runCase(
      lanes,
      expected,
      readyPattern = cycle => cycle % 6 != 0,
      startDelays = (0 until 16).map(lane => (lane % 4) * 3)
    )
  }

  test("streams more than FIFO depth through 16 lanes with backpressure") {
    // Ten elements per lane exceed the eight-entry input FIFO. Four lanes
    // contribute 1.0 to each (col,row), so every expected sum is 4.0.
    val lanes = (0 until 16).map { lane =>
      (0 until 10).map(col => Item(lane % 4, col, one))
    }
    val expected = (0 until 10).flatMap { col =>
      (0 until 4).map(row => Item(row, col, 0x4400))
    }
    runCase(lanes, expected, cycle => cycle % 9 >= 4)
  }
}
