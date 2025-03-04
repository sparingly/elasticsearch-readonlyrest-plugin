/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.integration

import java.util.Base64

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Group, IndexWithAliases, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.containers.LdapContainer
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueList

class GroupsRuleAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with ForAllTestContainer
    with Inside {

  private val (pub, secret) = Random.generateRsaRandomKeys

  override val container: LdapContainer = new LdapContainer("LDAP1", "test_example.ldif")

  override protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def configYaml: String =
    s"""
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Allowed only for group3 and group4"
      |    groups: [group3, group4]
      |    indices: ["g34_index"]
      |
      |  - name: "Allowed only for group1 and group2"
      |    groups: [group1, group2]
      |    indices: ["g12_index"]
      |
      |  - name: "Allowed only for group5"
      |    groups: ["@explode{jwt:roles}"]
      |    indices: ["g5_index"]
      |    jwt_auth: "jwt1"
      |
      |  - name: "::ELKADMIN::"
      |    kibana_access: unrestricted
      |    groups: ["admin"]
      |
      |  users:
      |
      |  - username: user1-proxy-id
      |    groups: ["group1"]
      |    proxy_auth:
      |      proxy_auth_config: "proxy1"
      |      users: ["user1-proxy-id"]
      |
      |  - username: user2
      |    groups: ["group3", "group4"]
      |    auth_key: "user2:pass"
      |
      |  - username: user3
      |    groups: ["group5"]
      |    jwt_auth: "jwt1"
      |
      |  - username: "*"
      |    groups: ["personal_admin", "admin", "admin_ops", "admin_dev"]
      |    ldap_auth:
      |      name: ldap1
      |      groups: ["group3"]
      |
      |  proxy_auth_configs:
      |
      |  - name: "proxy1"
      |    user_id_header: "X-Auth-Token"
      |
      |  jwt:
      |
      |  - name: jwt1
      |    signature_algo: "RSA"
      |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
      |    user_claim: "userId"
      |    roles_claim: roles
      |
      |  ######### LDAP SERVERS CONFIGURATION ########################
      |  # users:                                                    #
      |  #   * cartman:user2                                         #
      |  #   * bong:user1                                            #
      |  #   * morgan:user1                                          #
      |  #   * Bìlbö Bággįnš:user2                                   #
      |  # groups:                                                   #
      |  #   * group1: cartman, bong                                 #
      |  #   * group2: morgan, Bìlbö Bággįnš                         #
      |  #   * group3: morgan, cartman, bong                         #
      |  #############################################################
      |  ldaps:
      |    - name: ldap1
      |      host: "${container.ldapHost}"
      |      port: ${container.ldapPort}
      |      ssl_enabled: false                                        # default true
      |      ssl_trust_all_certs: true                                 # default false
      |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
      |      bind_password: "password"                                 # skip for anonymous bind
      |      search_user_base_DN: "ou=People,dc=example,dc=com"
      |      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      |      user_id_attribute: "uid"                                  # default "uid"
      |      unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
      |      connection_pool_size: 10                                  # default 30
      |      connection_timeout_in_sec: 10                             # default 1
      |      request_timeout_in_sec: 10                                # default 1
      |      cache_ttl_in_sec: 60                                      # default 0 - cache disabled
      |
    """.stripMargin

  "An ACL" when {
    "proxy auth is used together with groups" should {
      "allow to proceed" when {
        "proxy auth user is correct one" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-proxy-id")),
            filteredIndices = Set(indexName("g12_index")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("group1")))
          }
        }
      }
      "not allow to proceed" when {
        "proxy auth user is unknown" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-invalid")),
            filteredIndices = Set(indexName("g12_index")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case ForbiddenByMismatched(causes) =>
            causes.toNonEmptyList.toList should have size 1
            causes.toNonEmptyList.head should be (Cause.OperationNotAllowed)
          }
        }
      }
    }
    "jwt auth is used together with groups" should {
      "allow to proceed" when {
        "at least one of user's roles is declared in groups" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user3",
            "roles" := List("group5", "group6", "group7")
          ))
          val request = MockRequestContext.indices.copy(
            headers = Set(header("Authorization", s"Bearer ${jwt.stringify()}")),
            filteredIndices = Set(indexName("g*")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 3
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user3"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("group5")))
          }
        }
      }
    }
    "ldap auth with groups mapping is used together with groups" should {
      "allow to proceed" when {
        "user can be authenticated and authorized (externally and locally)" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(
              basicAuthHeader("morgan:user1"),
              header("x-ror-current-group", "admin")
            ),
            filteredIndices = Set(indexName(".kibana")),
            allIndicesAndAliases = Set(IndexWithAliases(localIndexName(".kibana"), Set.empty))
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("morgan"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("admin")))
          }
        }
      }
    }
  }

  private def allIndicesAndAliasesInTheTestCase() = Set(
    IndexWithAliases(localIndexName("g12_index"), Set.empty),
    IndexWithAliases(localIndexName("g34_index"), Set.empty),
    IndexWithAliases(localIndexName("g5_index"), Set.empty)
  )
}
