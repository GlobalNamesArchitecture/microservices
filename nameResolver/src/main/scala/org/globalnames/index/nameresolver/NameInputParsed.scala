package org.globalnames
package index
package nameresolver

import org.apache.commons.lang.WordUtils
import thrift.nameresolver.NameInput
import parser.{ScientificNameParser => snp}

final case class NameInputParsed(ni: NameInput) {
  val (firstWordCorrectlyCapitalised: Boolean, valueCapitalised: String) = {
    if (ni.value.isEmpty) {
      (true, ni.value)
    } else {
      val parts = ni.value.split("\\s", 2)
      val firstWord = parts(0)
      val rest = parts.drop(1).headOption.map { x =>
        ni.value.charAt(firstWord.length).toString + x
      }.getOrElse("")
      val firstWordCapitalised = WordUtils.capitalize(firstWord.toLowerCase)
      val firstWordIsCapitalised = firstWordCapitalised == firstWord
      val valueCapitalised = firstWordCapitalised + rest
      (firstWordIsCapitalised, valueCapitalised)
    }
  }

  val parsed: parser.RenderableResult =
    snp.instance.fromString(valueCapitalised)

  val nameInput: NameInput = ni.copy(suppliedId = ni.suppliedId.map { _.trim })
}
