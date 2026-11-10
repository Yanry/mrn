import spinal.core._

/** Elaboration-time pipeline metadata and a balanced leading-bit encoder. */
object FP16Pipeline {
  val stages = 4

  def leadingIndex(value: UInt): UInt = {
    val paddedWidth = 1 << log2Up(value.getWidth)
    def search(word: UInt): UInt = {
      val width = word.getWidth
      val indexWidth = math.max(1, log2Up(width))
      if (width == 1) U(0, 1 bits)
      else {
        val half = width / 2
        val high = word(width - 1 downto half)
        val low = word(half - 1 downto 0)
        val highIndex = search(high).resize(indexWidth) | U(half, indexWidth bits)
        Mux(high.orR, highIndex, search(low).resize(indexWidth))
      }
    }
    search(value.resize(paddedWidth)).resize(log2Up(value.getWidth))
  }
}
