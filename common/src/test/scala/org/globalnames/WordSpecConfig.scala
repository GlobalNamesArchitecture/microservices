package org.globalnames

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

trait WordSpecConfig extends WordSpec with Matchers with OptionValues with BeforeAndAfterEach
                        with BeforeAndAfterAll with ScalaFutures {
  def launchConditions: Boolean = true

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    while (!launchConditions) {
      Thread.sleep(100)
    }
  }
}
