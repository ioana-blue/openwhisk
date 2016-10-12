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

package whisk.core.entitlement

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import whisk.common.TransactionId
import whisk.core.controller.RejectRequest
import whisk.core.database.NoDocumentException
import whisk.core.entity.SequenceExec
import whisk.core.entity.WhiskAction
import whisk.core.entity.types.EntityStore
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.FullyQualifiedEntityName

import spray.http.StatusCodes.MethodNotAllowed
import spray.http.StatusCodes.NotFound

import Privilege.Privilege

protected[core] class ActionCollection(entityStore: EntityStore) extends Collection(Collection.ACTIONS) {
    /**
     * Computes implicit rights for an invoke on a sequence action.
     * For all other operations and for atomic actions defers to super class.
     * For sequences, fetches the resource and checks whether the invoke is allowed for
     * each individual action.
     */
    protected[core] override def implicitRights(namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        if (right != Privilege.ACTIVATE) {
            super.implicitRights(namespaces, right, resource)
        } else {
            resource.entity map { action =>
                // resolve the action based on the package bindings and check the rights
                resolveActionAndCheckRights(namespaces, right, FullyQualifiedEntityName(resource.namespace, EntityName(action)))
            } getOrElse {
                // this means there is no entity, invoke on a collection without an entity
                // it really shouldn't get here, this request should be rejected earlier
                // TODO: shall we log an error since this request should have been rejected earlier?
                Future.failed(RejectRequest(MethodNotAllowed))
            }
        }
    }

    /**
     * resolve the action based on the package binding (if any) and check its rights
     */
    private def resolveActionAndCheckRights(namespaces: Set[String], right: Privilege, entity: FullyQualifiedEntityName)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        WhiskAction.resolveAction(entityStore, entity) flatMap { action =>
            info(this, s"Checking right $right for a resolved action $action")
            // irrespective of the right requested, READ right on package (if any) is required
            checkPackageReadRights(namespaces, entity.path) flatMap { packageRight =>
                if (packageRight) {
                    WhiskAction.get(entityStore, action.toDocId) flatMap { wskaction =>
                        wskaction.exec match {
                            case SequenceExec(_, components) =>
                                info(this, s"Checking right '$right' for a sequence $wskaction' with components '${components}'")
                                val rights = components map { c => resolveActionAndCheckRights(namespaces, right, c) }
                                // collapse all futures from the sequence into a single future
                                val compRights = Future.sequence(rights)
                                // check all rights are true
                                compRights map { seq => seq.forall(_ == true) }
                            case _ => // this is not a sequence, defer to super
                                info(this, s"Check right $right for an atomic action $action")
                                // TODO: does it make a difference if this action is in a default package or not?
                                if (action.path.defaultPackage) {
                                    val actionResource = Resource(action.path, Collection(Collection.ACTIONS), Some(action.name.name))
                                    super.implicitRights(namespaces, right, actionResource)
                                } else {
                                    // this is an action in a package for which the READ rights were checked
                                    Future.successful(true)
                                }
                        }
                    }
                } else {
                    // read on package not allowed
                    Future.successful(false)
                }
            }
        } recoverWith {
            case t: NoDocumentException => Future.failed(RejectRequest(NotFound))
        }
    }

    /**
     * check package read rights, if the package exists, otherwise return true
     */
    private def checkPackageReadRights(namespaces: Set[String], entityPath: EntityPath)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        if (entityPath.defaultPackage) {
            // no package to check
            Future.successful(true)
        } else {
            val packageResource = Resource(entityPath.root, Collection(Collection.PACKAGES), Some(entityPath.last.name))
            // irrespective of right, one needs READ right on the package
            packageResource.collection.implicitRights(namespaces, Privilege.READ, packageResource)
        }
    }
}
