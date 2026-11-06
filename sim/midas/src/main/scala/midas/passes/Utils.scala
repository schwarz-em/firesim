// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import java.io.File

object Utils {
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head
    else {
      val left  = cat(es.slice(0, es.length / 2))
      val right = cat(es.slice(es.length / 2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, UnknownType)
    }

  def orderedCat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head
    else {
      DoPrim(PrimOps.Cat, Seq(orderedCat(es.tail), es.head), Nil, UnknownType)
    }

  // Takes a circuit state and writes it out to the target-directory by selecting
  // an appropriate emitter for its form
  def writeState(state: CircuitState, name: String): Unit = {
    val td      = state.annotations.collectFirst({ case TargetDirAnnotation(value) => value })
    val file    = td match {
      case Some(dir) => new File(dir, name)
      case None      => new File(name)
    }
    state.form match {
      case LowForm | MidForm | HighForm =>
      case _                            => throw new RuntimeException("Cannot select emitter for unrecognized form.")
    }
    // Streamed: large designs serialize past the JVM's 2 GiB String limit.
    val writer = new java.io.BufferedWriter(new java.io.FileWriter(file), 1 << 20)
    try firrtl.ir.Serializer.lazily(state.circuit).foreach(writer.write)
    finally writer.close()
  }
}

// Writes out the circuit to a file for debugging
class EmitFirrtl(fileName: String) extends firrtl.Transform {

  def inputForm     = HighForm
  def outputForm    = HighForm
  override def name = s"[Golden Gate] Debugging Emission Pass: $fileName"

  def execute(state: CircuitState) = {
    Utils.writeState(state, fileName)
    state
  }
}
