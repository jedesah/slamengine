package blueeyes.core.http

import org.specs2.mutable.Specification
import blueeyes.core.http.MimeTypes._
import TCodings._
import org.specs2.matcher.MustThrownMatchers

class TCodingSpec extends Specification with MustThrownMatchers {

  "TCoding:  Should parse \"trailers, deflate\" as (trailers. deflate) produce Nil for \"12\"" in {
    TCodings.parseTCodings("trailers, deflate") mustEqual (List(trailers, deflate))
    TCodings.parseTCodings("12") mustEqual (Nil)
  }

  "TCoding:  Should parse CustomTCoding" in {
    TCodings.parseTCodings("foo, bar") mustEqual (List(CustomTCoding("foo"), CustomTCoding("bar")))
  }
}
