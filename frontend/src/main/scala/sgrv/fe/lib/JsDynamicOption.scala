package sgrv.fe.lib

import scala.scalajs.js

trait JsType[A]:
  def typeName: String

object JsType:
  given JsType[String] with
    override val typeName: String = "string"

extension (value: Option[js.Dynamic])
  def filterJs[A](using jsType: JsType[A]): Option[A] =
    value
      .filter(js.typeOf(_) == jsType.typeName)
      .map(_.asInstanceOf[A])

extension (value: js.Dynamic)
  def asNonEmptyString: Option[String] =
    Option(value)
      .filterJs[String]
      .filter(_.nonEmpty)
