// See LICENSE for license details.

package goldengate.tests

import org.scalatest.flatspec.AnyFlatSpec

import midas.models.DRAMProgrammableTimings

class DRAMProgrammableTimingsSpec extends AnyFlatSpec {
  behavior.of("DRAMProgrammableTimings")

  it should "use the DDR3-2133 tRRD reset default" in {
    val defaults = new DRAMProgrammableTimings().getDefaults().toMap
    assert(defaults("tRRD") == BigInt(5))
  }
}
