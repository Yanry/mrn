import spinal.core._
import config.FloatConfig

/** Dedicated binary16 four-stage elastic arithmetic; RNE, gradual underflow. */
case class FP16Add() extends Component {
  // Keep the public port name stable when active-low Spinal generation defaults to resetn.
  ClockDomain.current.reset.setName("reset")
  val cfg = FloatConfig.FP16
  val m = 10
  val ew = 9
  val pw = 41
  val io = new Bundle {
    val a, b = in Bits(16 bits)
    val result = out Bits(16 bits)
    val flags = out Bits(5 bits)
    val in_valid = in Bool()
    val in_ready = out Bool()
    val out_valid = out Bool()
    val out_ready = in Bool()
  }
  // Four elastic stages. Only valid bits need reset; payload is sampled only when valid.
  val valid = Vec.fill(FP16Pipeline.stages)(Reg(Bool()) init(False))
  val ready = Vec(Bool(), FP16Pipeline.stages)
  for (i <- FP16Pipeline.stages - 1 to 0 by -1) {
    ready(i) := !valid(i) || (if (i == FP16Pipeline.stages - 1) io.out_ready else ready(i + 1))
    when(ready(i)) { valid(i) := (if (i == 0) io.in_valid else valid(i - 1)) }
  }
  io.in_ready := ready(0)
  io.out_valid := valid(FP16Pipeline.stages - 1)
  def stage[T <: Data](value: T, index: Int): T = {
    val q = Reg(cloneOf(value))
    when(ready(index) && (if (index == 0) io.in_valid else valid(index - 1))) { q := value }
    q
  }
  case class Decoded() extends Bundle {
    val sign = Bool()
    val zero = Bool()
    val nan = Bool()
    val inf = Bool()
    val invalidNaN = Bool()
    val sig = UInt(m + 1 bits)
    val exponent = SInt(ew bits)
  }
  def decode(x: Bits): Decoded = {
    val d = Decoded()
    val exp = x(m + cfg.exponentBits - 1 downto m).asUInt
    val frac = x(m - 1 downto 0).asUInt
    d.sign := x(cfg.width - 1)
    d.zero := (exp === 0) && (frac === 0)
    d.nan := (if (cfg.finiteOnly) (exp === cfg.expMax) && (frac === cfg.fracMax)
              else (exp === cfg.expMax) && (frac =/= 0))
    d.inf := (if (cfg.finiteOnly) False else (exp === cfg.expMax) && (frac === 0))
    d.invalidNaN := (if (cfg.finiteOnly) d.nan else d.nan && !frac(m - 1))
    d.sig := Mux(exp === 0, frac.resize(m + 1), U(1 << m, m + 1 bits) | frac.resize(m + 1))
    d.exponent := Mux(exp === 0, S(cfg.emin, ew bits), exp.resize(ew).asSInt - cfg.bias)
    d
  }
  val a = decode(io.a)
  val b = decode(io.b)
  val specialValid = Bool()
  val specialResult = Bits(16 bits)
  val specialFlags = Bits(5 bits)
  specialValid := False
  specialResult := 0
  specialFlags := 0
  when(a.nan || b.nan) {
    specialValid := True
    specialResult := Mux(a.nan, a.sign, b.sign).asBits ## B(cfg.canonicalNaN, 15 bits)
    specialFlags := Mux(a.invalidNaN || b.invalidNaN, B"10000", B"00000")
  } elsewhen(a.inf && b.inf && (a.sign =/= b.sign)) {
    specialValid := True
    specialResult := B(cfg.canonicalNaN, 16 bits)
    specialFlags := B"10000"
  } elsewhen(a.inf || b.inf) {
    specialValid := True
    specialResult := Mux(a.inf, a.sign, b.sign).asBits ## B(cfg.infinity, 15 bits)
  }
  val bypass4 = stage(stage(stage(specialValid.asBits ## specialResult ## specialFlags, 0), 1), 2)
  val base = -24
  val sameSignIn = a.sign === b.sign
  // Finite binary16 magnitude codes are monotonic. Specials use bypass4.
  val aLargerIn = io.a(14 downto 0).asUInt >= io.b(14 downto 0).asUInt
  val equalMagnitude = io.a(14 downto 0) === io.b(14 downto 0)
  val alignedA = (a.sig.resize(pw) |<< (a.exponent + 14).asUInt).resize(pw)
  val alignedB = (b.sig.resize(pw) |<< (b.exponent + 14).asUInt).resize(pw)
  // S1: exact alignment, magnitude ordering and final sign selection.
  val ax = stage(Mux(aLargerIn, alignedA, alignedB), 0)
  val bx = stage(Mux(aLargerIn, alignedB, alignedA), 0)
  val sameSign = stage(sameSignIn, 0)
  val resultSign = Mux(equalMagnitude && !sameSignIn, False,
    Mux(aLargerIn, a.sign, b.sign))
  val sign2 = stage(resultSign, 0)
  // S2: shared add/subtract unit, subtracting smaller from larger.
  val subtract = !sameSign
  val rhs = bx ^ Mux(subtract, U((BigInt(1) << pw) - 1, pw bits), U(0, pw bits))
  // Six short local adders with parallel group-carry lookahead.
  // Long carry propagation is not left to a single 41-bit ripple chain.
  val chunkBits = 7
  val groups = (pw + chunkBits - 1) / chunkBits
  val partial = (0 until groups).map { i =>
    val lo = i * chunkBits
    val hi = math.min(pw, lo + chunkBits) - 1
    val w = hi - lo + 1
    ax(hi downto lo).resize(w + 1) + rhs(hi downto lo).resize(w + 1)
  }
  val generates = (0 until groups).map { i => partial(i).msb }
  val propagates = (0 until groups).map { i =>
    val lo = i * chunkBits
    val hi = math.min(pw, lo + chunkBits) - 1
    (ax(hi downto lo) ^ rhs(hi downto lo)).andR
  }
  val carries = Vec(Bool(), groups + 1)
  carries(0) := subtract
  for (i <- 1 to groups) {
    val terms = (0 until i).map { j =>
      var term = generates(j)
      for (k <- j + 1 until i) term = term && propagates(k)
      term
    }
    var fromInput = subtract
    for (k <- 0 until i) fromInput = fromInput && propagates(k)
    carries(i) := terms.reduce(_ || _) || fromInput
  }
  val sum = UInt(pw bits)
  for (i <- 0 until groups) {
    val lo = i * chunkBits
    val hi = math.min(pw, lo + chunkBits) - 1
    val w = hi - lo + 1
    sum(hi downto lo) := partial(i)(w - 1 downto 0) + carries(i).asUInt.resize(w)
  }
  // S2 ends at the exact sum: leading-bit detection no longer follows
  // the add/subtract carry network in the same cycle.
  val sum2 = stage(sum, 1)
  val signAt2 = stage(sign2, 1)
  // S3: balanced leading-bit detection; exact sum and sign advance together.
  val sum3 = stage(sum2, 2)
  val sign3 = stage(signAt2, 2)
  val h3 = stage(FP16Pipeline.leadingIndex(sum2), 2)
  val exponent = h3.resize(ew).asSInt + base
  // Round the exact sum to a significand integer at any signed shift.
  // Static branches bound all shifts; no truncated shift amount can wrap.
  def rounded(shift: SInt) = new Area {
    val retained = UInt(m + 1 bits)
    val guard = Bool()
    val sticky = Bool()
    retained := 0; guard := False; sticky := False
    when(shift > pw) { sticky := sum3.orR }
    for (s <- -m to pw) {
      when(shift === s) {
        if (s <= 0) retained := (sum3 << (-s)).resize(m + 1)
        else {
          retained := (sum3 >> s).resize(m + 1)
          guard := sum3(s - 1)
          if (s > 1) sticky := sum3(s - 2 downto 0).orR
        }
      }
    }
    val nx = guard || sticky
    val increment = guard && (sticky || retained(0))
    val value = retained.resize(m + 2) + increment.asUInt.resize(m + 2)
  }
  val normal = rounded(h3.resize(ew).asSInt - m)
  val carry = normal.value(m + 1)
  val after = exponent + carry.asUInt.resize(ew).asSInt
  val fraction = Mux(carry, U(0, m bits), normal.value(m - 1 downto 0))
  val sub = rounded(S(cfg.emin - m - base, ew bits))
  val overflow = (after > cfg.emax) ||
    (if (cfg.finiteOnly) (after === cfg.emax) && (fraction === cfg.fracMax) else False)
  val magnitude = Bits(cfg.width - 1 bits)
  magnitude := 0
  val resultBits = Bits(cfg.width bits)
  val flagsBits = Bits(5 bits)
  flagsBits := 0
  when(exponent >= cfg.emin) {
    magnitude := ((after + cfg.bias).resize(cfg.exponentBits).asBits ## fraction.asBits)
    flagsBits(0) := normal.nx
  } otherwise {
    // A carry out of the fraction naturally encodes the minimum normal.
    magnitude := sub.value.resize(cfg.width - 1).asBits
    flagsBits(0) := sub.nx
    flagsBits(1) := sub.nx && (after < cfg.emin)
  }
  when(overflow) {
    magnitude := B(if (cfg.finiteOnly) cfg.maxFinite else cfg.infinity, cfg.width - 1 bits)
    flagsBits := B"00101"
  }
  resultBits := sign3.asBits ## magnitude
  when(sum3 === 0) {
    resultBits := sign3.asBits ## B(0, cfg.width - 1 bits)
    flagsBits := 0
  }
  when(bypass4(cfg.width + 5)) {
    resultBits := bypass4(cfg.width + 4 downto 5)
    flagsBits := bypass4(4 downto 0)
  }
  // S4: normalization, RNE and registered output.
  io.result := stage(resultBits, 3)
  io.flags := stage(flagsBits, 3)
}
object Generate extends App {
  SpinalConfig(targetDirectory = "rtl", defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW)).generateVerilog(FP16Add())
}
