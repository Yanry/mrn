package config

/** Elaboration-time configuration; all three operands and result use the same format. */
case class FloatConfig(exponentBits: Int, fractionBits: Int, bias: Int,
                       finiteOnly: Boolean = false) {
  require(exponentBits >= 2 && exponentBits <= 8)
  require(fractionBits >= 1 && fractionBits <= 23)
  require(bias >= 1 && bias < (1 << exponentBits) - 1)
  val width = 1 + exponentBits + fractionBits
  val expMax = (1 << exponentBits) - 1
  val fracMax = (1 << fractionBits) - 1
  val emin = 1 - bias
  val emax = expMax - bias - (if (finiteOnly) 0 else 1)
  val maxFinite = ((expMax - (if (finiteOnly) 0 else 1)) << fractionBits) |
    (fracMax - (if (finiteOnly) 1 else 0))
  val infinity = expMax << fractionBits
  val canonicalNaN = infinity | (if (finiteOnly) fracMax else 1 << (fractionBits - 1))
}
object FloatConfig {
  val FP8E4M3 = FloatConfig(4, 3, 7, finiteOnly = true)
  val FP8E5M2 = FloatConfig(5, 2, 15)
  val FP16 = FloatConfig(5, 10, 15)
  val presets = Seq("e4m3" -> FP8E4M3, "e5m2" -> FP8E5M2, "fp16" -> FP16)
  def named(name: String): FloatConfig = presets.find(_._1 == name.toLowerCase)
    .map(_._2).getOrElse(throw new IllegalArgumentException(s"Unknown format: $name"))
}
