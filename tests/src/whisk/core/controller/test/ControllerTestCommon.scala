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

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.language.postfixOps

import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import akka.event.Logging.{ InfoLevel, LogLevel }
import spray.http.BasicHttpCredentials
import spray.json.JsString
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest
import whisk.common.{ Logging, TransactionCounter, TransactionId }
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.controller.WhiskActionsApi
import whisk.core.controller.WhiskServices
import whisk.core.database.test.DbUtils
import whisk.core.entitlement.{ Collection, EntitlementService, LocalEntitlementService }
import whisk.core.entity._
import whisk.core.loadBalancer.LoadBalancer


protected trait ControllerTestCommon
    extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with TransactionCounter
    with DbUtils
    with WhiskServices
    with HttpService
    with Logging {

    override val actorRefFactory = null
    implicit val routeTestTimeout = RouteTestTimeout(90 seconds)

    implicit val actorSystem = system // defined in ScalatestRouteTest
    val executionContext = actorSystem.dispatcher

    override val whiskConfig = new WhiskConfig(WhiskActionsApi.requiredProperties)
    assert(whiskConfig.isValid)

    override val loadBalancer = new DegenerateLoadBalancerService(whiskConfig, InfoLevel)
    override val entitlementService: EntitlementService = new LocalEntitlementService(whiskConfig, loadBalancer)

    override val activationId = new ActivationId.ActivationIdGenerator() {
        // need a static activation id to test activations api
        private val fixedId = ActivationId()
        override def make = fixedId
    }

    override val consulServer = "???"

    val entityStore = WhiskEntityStore.datastore(whiskConfig)
    val activationStore = WhiskActivationStore.datastore(whiskConfig)
    val authStore = WhiskAuthStore.datastore(whiskConfig)

    def createTempCredentials(implicit transid: TransactionId) = {
        val auth = WhiskAuth(Subject(), AuthKey())
        put(authStore, auth)
        waitOnView(authStore, auth.uuid, 1)
        (auth, BasicHttpCredentials(auth.uuid(), auth.key()))
    }

    def deleteAction(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAction.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteActivation(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskActivation.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskActivation.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteTrigger(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskTrigger.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteRule(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskRule.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskRule.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteAuth(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAuth.get(authStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAuth.del(authStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deletePackage(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskPackage.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskPackage.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def stringToFullyQualifiedName(s: String) = FullyQualifiedEntityName.serdes.read(JsString(s))

    /**
     * Makes a simple sequence action and installs it in the db (no call to wsk api/cli).
     * All actions are in the default package.
     *
     * @param sequenceName the name of the sequence
     * @param ns the namespace to be used when creating the component actions and the sequence action
     * @param components the names of the actions (entity names, no namespace)
     */
    def putSimpleSequenceInDB(sequenceName: String, ns: EntityPath, components: Vector[String])(
        implicit tid: TransactionId) = {
        val seqAction = makeSimpleSequence(sequenceName, ns, components)
        put(entityStore, seqAction)
    }

    /**
     * Makes a simple sequence action and installs it in the db (no call to wsk api/cli).
     * All actions are in the default package.
     *
     * @param sequenceName the name of the sequence
     * @param nsSeq the namespace to be used when creating the sequence action
     * @param components the names of the actions (entity names, no namespace)
     * @param componentNs the namespaces to be used for components
     */
    def putSimpleSequenceInDBWithNamespaces(sequenceName: String, ns: EntityPath, components: Vector[String], componentNs: Vector[EntityPath])(
        implicit tid: TransactionId) = {
        val seqAction = makeSimpleSequenceWithNamespaces(sequenceName, ns, components, componentNs)
        put(entityStore, seqAction)
    }

    /**
     * Returns a WhiskAction that can be used to create/update a sequence.
     * If instructed to do so, installs the component actions in the db.
     * All actions are in the default package. Same namespace is used for components and sequence.
     *
     * @param sequenceName the name of the sequence
     * @param ns the namespace to be used when creating the component actions and the sequence action
     * @param componentNames the names of the actions (entity names, no namespace)
     * @param installDB if true, installs the component actions in the db (default true)
     */
    def makeSimpleSequence(sequenceName: String, ns: EntityPath, componentNames: Vector[String], installDB: Boolean = true)(
        implicit tid: TransactionId): WhiskAction = {
        // create the vector of namespaces
        val componentNs = Vector.fill(componentNames.size)(ns)
        makeSimpleSequenceWithNamespaces(sequenceName, ns, componentNames, componentNs, installDB)
    }

    /**
     * Returns a WhiskAction that can be used to create/update a sequence.
     * If instructed to do so, installs the component actions in the db.
     * All actions are in the default package. The namespaces for the sequence and the components are provided.
     *
     * @param sequenceName the name of the sequence
     * @param nsSeq the namespace to be used when creating the component actions and the sequence action
     * @param componentNames the names of the actions (entity names, no namespace)
     * @param componentNs the namespaces to be used for components
     * @param installDB if true, installs the component actions in the db (default true)
     */
    def makeSimpleSequenceWithNamespaces(sequenceName: String, nsSeq: EntityPath, componentNames: Vector[String], componentNs: Vector[EntityPath], installDB: Boolean = true)(
        implicit tid: TransactionId): WhiskAction = {
        assert(componentNames.size == componentNs.size)
        val wskActionsNsNamePair = componentNs.zip(componentNames.map(EntityName(_)))

        if (installDB) {
            // create bogus wsk actions
            val wskActions = wskActionsNsNamePair.toSet[(EntityPath, EntityName)] map { pair =>
                WhiskAction(pair._1, pair._2, Exec.js("??")) }
            // add them to the db
            wskActions.foreach { put(entityStore, _) }
        }
        // add namespace to component names
        //val components = componentNames map { c => s"/$ns/$c" }
        val components = wskActionsNsNamePair map { pair =>
            s"/${pair._1}/${pair._2}"
        }
        // create wsk action for the sequence
        val fqenComponents = components.toVector map { c => stringToFullyQualifiedName(c) }
        WhiskAction(nsSeq, EntityName(sequenceName), Exec.sequence(fqenComponents))
    }

    object MakeName {
        @volatile var counter = 1
        def next(prefix: String = "test")(): EntityName = {
            counter = counter + 1
            EntityName(s"${prefix}_name$counter")
        }
    }

    setVerbosity(InfoLevel)
    Collection.initialize(entityStore, InfoLevel)
    entityStore.setVerbosity(InfoLevel)
    activationStore.setVerbosity(InfoLevel)
    authStore.setVerbosity(InfoLevel)
    entitlementService.setVerbosity(InfoLevel)

    val ACTIONS = Collection(Collection.ACTIONS)
    val TRIGGERS = Collection(Collection.TRIGGERS)
    val RULES = Collection(Collection.RULES)
    val ACTIVATIONS = Collection(Collection.ACTIVATIONS)
    val NAMESPACES = Collection(Collection.NAMESPACES)
    val PACKAGES = Collection(Collection.PACKAGES)

    after {
        cleanup()
    }

    override def afterAll() {
        println("Shutting down cloudant connections");
        entityStore.shutdown()
        activationStore.shutdown()
        authStore.shutdown()
    }
}

class DegenerateLoadBalancerService(config: WhiskConfig, verbosity: LogLevel)
    extends LoadBalancer {

    // unit tests that need an activation via active ack/fast path should set this to value expected
    var whiskActivationStub: Option[WhiskActivation] = None

    override def getUserActivationCounts: Map[String, Long] = Map()

    override def publish(msg: ActivationMessage, timeout: FiniteDuration)(implicit transid: TransactionId): (Future[Unit], Future[WhiskActivation]) =
        (Future.successful {},
         whiskActivationStub map {
            activation => Future.successful(activation)
        } getOrElse Future.failed(new IllegalArgumentException("Unit test does not need fast path")))

}
