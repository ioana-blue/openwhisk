/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import whisk.common.TransactionId
import whisk.core.controller.WhiskActionsApi

import whisk.core.controller.Authenticate
import whisk.core.controller.RejectRequest
import whisk.core.entitlement.Privilege.ACTIVATE
import whisk.core.entitlement.Privilege.DELETE
import whisk.core.entitlement.Privilege.PUT
import whisk.core.entitlement.Privilege.READ
import whisk.core.entitlement.Privilege.REJECT
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entitlement.Privilege
import spray.http.StatusCodes._

import spray.httpx.SprayJsonSupport._


/**
 * Tests authorization handler which guards resources for the special case of sequences.
 *
 * Unit tests of the controller/entitlement service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communicate with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class SequenceAuthorizeTests
    extends ControllerTestCommon
    with Authenticate
    with WhiskActionsApi {

    behavior of "Authorize"

    val requestTimeout = 1 second
    val someUser = Subject().toIdentity(AuthKey())
    val adminUser = Subject("admin").toIdentity(AuthKey())
    val guestUser = Subject("anonym").toIdentity(AuthKey())

    def aname = MakeName.next("sequence_auth")

    /** tests with no packages and no binding first */

    /**
     * s -> a, b, c
     * b in a different namespace
     * create s works, invoke s forbidden
     * grant rights to b
     * entitlement check on s should return true
     */
    it should "create a simple sequence irrespective of the rights on the actions, but reject invocation if rights not met" in {
        implicit val tid = transid()
        val bName = s"b_${aname}"
        val compNames = Vector(s"a_${aname}", bName , s"c_${aname}")
        val compNs = Vector(guestUser.namespace, someUser.namespace, guestUser.namespace).map(c => EntityPath(c.name))
        val seqName = s"Seq_${aname}"
        val seqAction = makeSimpleSequenceWithNamespaces(seqName, EntityPath(guestUser.namespace.name), compNames, compNs)
        val collectionPath = s"/${guestUser.namespace}/${collection.path}"
         // create an action sequence --- should work, no checks in place
        Put(s"${collectionPath}/${seqName.name}", seqAction) ~> sealRoute(routes(guestUser)) ~> check {
            status should be(OK)
        }
        // invoke the action --- should be forbidden
        Post(s"${collectionPath}/${seqName.name}", seqAction) ~> sealRoute(routes(guestUser)) ~> check {
            status should be(Forbidden)
        }
        val someUserAllActionsResource = Resource(someUser.namespace.toPath, ACTIONS, None)
        val someUserNamespaceResource = Resource(someUser.namespace.toPath, NAMESPACES, Some(someUser.namespace.name))
        val bResource = Resource(someUser.namespace.toPath, ACTIONS, Some(bName))
        val seqActionResource = Resource(guestUser.namespace.toPath, ACTIONS, Some(seqName))
        // check entitlement also rejects
        Await.result(entitlementService.check(guestUser, ACTIVATE, seqActionResource), requestTimeout) should be(false) // should NOT be able to invoke seq s
        Await.result(entitlementService.grant(guestUser.subject, ACTIVATE, someUserAllActionsResource), requestTimeout) // grant read to b
        Await.result(entitlementService.check(someUser, ACTIVATE, bResource), requestTimeout) should be(true) // should be able to invoke b as someUser
        Await.result(entitlementService.check(guestUser, ACTIVATE, bResource), requestTimeout) should be(true) // should be able to invoke b as guestUser
        Await.result(entitlementService.grant(guestUser.subject, ACTIVATE, someUserNamespaceResource), requestTimeout)  should be(true)// grant activate to someUser namespace
        //Await.result(entitlementService.check(guestUser, ACTIVATE, seqActionResource), requestTimeout) should be(true) // should be able to invoke seq s
        Await.result(entitlementService.revoke(guestUser.subject, ACTIVATE, someUserAllActionsResource), requestTimeout) // revoke read to b
        Await.result(entitlementService.revoke(guestUser.subject, ACTIVATE, someUserNamespaceResource), requestTimeout) // grant activate to someUser namespace
        deleteAction(seqAction.docid)
    }
}
