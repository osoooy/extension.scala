package extension

import scala.math.BigDecimal.RoundingMode._

package object math {

  implicit final class BigDecimalOps(x: BigDecimal) {
    def roundQuot(unitOfMeasure: BigDecimal, mode: RoundingMode = DOWN): BigDecimal = {
      val d = x.bigDecimal
      val n = unitOfMeasure.bigDecimal
      d.divide(n, 0, mode.id)
    }

    def roundWith(unitOfMeasure: BigDecimal, mode: RoundingMode = DOWN): BigDecimal = {
      val d = x.bigDecimal
      val n = unitOfMeasure.bigDecimal
      val q = d.divide(n, 0, mode.id)

      new BigDecimal(q.multiply(n), x.mc)
    }
  }

  def D(s: String) = BigDecimal(s)
  implicit final class StringContext2Decimal(sc: StringContext) {
    def d() = D(sc.parts.mkString)
  }

  implicit final class String2Decimal(private val s: String) {

    def d = D(s)
  }

  final val Inf  = BigDecimal(1, -18)
  final val ZERO = "0".d
  final val ONE  = "1".d
  final val TWO  = "2".d
  final val TEN  = "10".d

}
