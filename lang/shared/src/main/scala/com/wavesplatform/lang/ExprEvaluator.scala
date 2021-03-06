package com.wavesplatform.lang

trait ExprEvaluator extends Versioned {
  def apply[T: TypeInfo](ctx: version.CtxT, expr: version.ExprT): Either[ExecutionError, T]
}
