package org.mule.weave.v2.module.io.functions

import java.net.ServerSocket

import org.mule.weave.v2.core.functions.EmptyFunctionValue
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.values.NumberValue
import org.mule.weave.v2.model.values.Value
import org.mule.weave.v2.util.TryClose

class FreePortFunctionValue extends EmptyFunctionValue {

  override def doExecute()(implicit ctx: EvaluationContext): Value[_] = {
    val port = TryClose(new ServerSocket(0), (ss: ServerSocket) => {
      ss.getLocalPort
    })
    NumberValue(port)
  }
}

