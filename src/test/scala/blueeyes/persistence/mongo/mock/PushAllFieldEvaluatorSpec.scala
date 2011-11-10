package blueeyes.persistence.mongo.mock

import org.specs2.mutable.Specification
import blueeyes.json.JsonAST._
import com.mongodb.MongoException
import MockMongoUpdateEvaluators._
import blueeyes.persistence.mongo._

class PushAllFieldEvaluatorSpec  extends Specification{
  "create new Array for not existing field" in {
    val operation = ("foo" pushAll (MongoPrimitiveInt(2))).asInstanceOf[MongoUpdateField]
    PushAllFieldEvaluator(JNothing, operation.filter) mustEqual(JArray(JInt(2) :: Nil))
  }
  "add new element existing field" in {
    val operation = ("foo" pushAll (MongoPrimitiveInt(3))).asInstanceOf[MongoUpdateField]
    PushAllFieldEvaluator(JArray(JInt(2) :: Nil), operation.filter) mustEqual(JArray(JInt(2) :: JInt(3) :: Nil))
  }
}