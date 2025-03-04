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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.{NonEmptyList, NonEmptySet}
import com.softwaremill.sttp.Method
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, Succeeded}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, GeneralIndexRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.IndexName.Remote
import tech.beshu.ror.accesscontrol.domain.Template.{ComponentTemplate, IndexTemplate, LegacyTemplate}
import tech.beshu.ror.accesscontrol.domain.TemplateOperation._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.mocks.{MockFilterableMultiRequestContext, MockGeneralIndexRequestContext, MockRequestContext, MockTemplateRequestContext}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class IndicesRuleTests extends AnyWordSpec with MockFactory {

  "An IndicesRule" should {
    "match" when {
      "no index passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(localIndexName("test"), Set.empty))
          ),
          found = Set(indexName("test")),
        )
      }
      "'_all' passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(localIndexName("test"), Set.empty))
          ),
          found = Set(indexName("test"))
        )
      }
      "'*' passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(localIndexName("test"), Set.empty))
          ),
          found = Set(indexName("test"))
        )
      }
      "one full name index passed, one full name index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("test")),
          found = Set(indexName("test"))
        )
      }
      "one wildcard index passed, one full name index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(localIndexName("test"), Set.empty)),
          ),
          found = Set(indexName("test"))
        )
      }
      "one full name index passed, one wildcard index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("t*")),
          requestIndices = Set(indexName("test")),
          found = Set(indexName("test"))
        )
      }
      "two full name indexes passed, the same two full name indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(indexName("test2"), indexName("test1")),
          found = Set(indexName("test2"), indexName("test1"))
        )
      }
      "two full name indexes passed, one the same, one different index configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(indexName("test1"), indexName("test3")),
          found = Set(indexName("test1"))
        )
      }
      "two matching wildcard indexes passed, two full name indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(indexName("*2"), indexName("*1")),
          found = Set(indexName("test1"), indexName("test2"))
        )
      }
      "two full name indexes passed, two matching wildcard indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(indexName("test2"), indexName("test1")),
          found = Set(indexName("test2"), indexName("test1"))
        )
      }
      "two full name indexes passed, one matching full name and one non-matching wildcard index configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(indexName("test1"), indexName("test3")),
          found = Set(indexName("test1"))
        )
      }
      "one matching wildcard index passed and one non-matching full name index, two full name indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(indexName("*1"), indexName("test3")),
          found = Set(indexName("test1"))
        )
      }
      "one full name alias passed, full name index related to that alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(indexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index"), Set(localIndexName("test-alias")))
            )
          ),
          found = Set(indexName("test-index"))
        )
      }
      "wildcard alias passed, full name index related to alias matching passed one configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(indexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index"), Set(localIndexName("test-alias")))
            )
          ),
          found = Set(indexName("test-index"))
        )
      }
      "one full name alias passed, wildcard index configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-index")),
          requestIndices = Set(indexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index"), Set(localIndexName("test-alias")))
            )
          ),
          found = Set(indexName("test-index"))
        )
      }
      "one alias passed, only subset of alias indices configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index1"), indexNameVar("test-index2")),
          requestIndices = Set(indexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index1"), Set(localIndexName("test-alias"))),
              IndexWithAliases(localIndexName("test-index2"), Set(localIndexName("test-alias"))),
              IndexWithAliases(localIndexName("test-index3"), Set(localIndexName("test-alias"))),
              IndexWithAliases(localIndexName("test-index4"), Set(localIndexName("test-alias")))
            )
          ),
          found = Set(indexName("test-index1"), indexName("test-index2"))
        )
      }
      "cross cluster index is used together with local index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("odd:test1*"), indexNameVar("local*")),
          requestIndices = Set(indexName("local_index*"), indexName("odd:test1_index*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("local_index1"), Set.empty),
              IndexWithAliases(localIndexName("local_index2"), Set.empty),
              IndexWithAliases(localIndexName("other"), Set.empty)
            )
          ),
          found = Set(
            indexName("local_index1"),
            indexName("local_index2"),
            indexName("odd:test1_index*")
          )
        )
      }
      "multi filterable request tries to fetch data for allowed and not allowed index" in {
        assertMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          indexPacks = Indices.Found(Set(indexName("test1"), indexName("test2"))) :: Nil,
          allowed = Indices.Found(Set(indexName("test1"))) :: Nil
        )
      }
    }
    "not match" when {
      "no index passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty
        )
      }
      "'_all' passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("_all"))
        )
      }
      "'*' passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(indexName("*"))
        )
      }
      "one full name index passed, different one full name index configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(indexName("test2"))
        )
      }
      "one wildcard index passed, non-matching index with full name configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(indexName("*2"))
        )
      }
      "one full name index passed, non-matching index with wildcard configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1")),
          requestIndices = Set(indexName("test2"))
        )
      }
      "two full name indexes passed, different two full name indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(indexName("test4"), indexName("test3"))
        )
      }
      "two wildcard indexes passed, non-matching two full name indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(indexName("*4"), indexName("*3"))
        )
      }
      "two full name indexes passed, non-matching two wildcard indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(indexName("test4"), indexName("test3"))
        )
      }
      "one full name alias passed, full name index with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(indexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index"), Set.empty),
              IndexWithAliases(localIndexName("test-index2"), Set(localIndexName("test-alias")))
            )
          )
        )
      }
      "wildcard alias passed, full name index with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(indexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index"), Set.empty),
              IndexWithAliases(localIndexName("test-index2"), Set(localIndexName("test-alias")))
            )
          )
        )
      }
      "full name index passed, index alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12-alias")),
          requestIndices = Set(indexName("test-index1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(localIndexName("test-index1"), Set(localIndexName("test12-alias"))),
              IndexWithAliases(localIndexName("test-index2"), Set(localIndexName("test12-alias"))),
              IndexWithAliases(localIndexName("test-index3"), Set(localIndexName("test34-alias"))),
              IndexWithAliases(localIndexName("test-index4"), Set(localIndexName("test34-alias")))
            )
          )
        )
      }
    }
  }

  "An IndicesRule for request with remote indices" should {
    "match" when {
      // todo:
//      val userTestSearchManager = new SearchManager(basicAuthClient("test", "test"))
//
//      //      val result1 = userTestSearchManager.asyncSearch("*-logs-smg-stats-*")
//      //      val result2 = userTestSearchManager.asyncSearch("*:*-logs-smg-stats-*")
//      //      val result3 = userTestSearchManager.asyncSearch("*-logs-smg-*")
//      val result4 = userTestSearchManager.asyncSearch("*:*-logs-smg-*")
//
//      //      if(result1.isSuccess) {
//      //        println(s"R1: ${result1.responseJson.toString()}")
//      //      }
//      //      if(result2.isSuccess) {
//      //        println(s"R2: ${result2.responseJson.toString()}")
//      //      }
//      //      if(result3.isSuccess) {
//      //        println(s"R3: ${result3.responseJson.toString()}")
//      //      }
//      if(result4.isSuccess) {
//        println(s"R4: ${result4.responseJson.toString()}")
//      }

//      documentManager.createFirstDoc("c01-logs-smg-stats-2020-03-27",  ujson.read("""{"counter1":"50"}""")).force()
//      documentManager.createFirstDoc("c01-logs-smg-stats-2020-03-28",  ujson.read("""{"counter1":"50"}""")).force()
//      documentManager.createFirstDoc("c01-logs-smg-stats-2020-03-29",  ujson.read("""{"counter1":"50"}""")).force()
//      "test" in {
//        assertMatchRuleForIndexRequest(
//          configured = NonEmptySet.of(indexNameVar("*-logs-smg-stats-*")),//, indexNameVar("*:*-logs-smg-stats-*")),
//          requestIndices = Set(indexName("*:*-logs-smg-*")),
//          modifyRequestContext = _.copy(
//            allIndicesAndAliases = Set(IndexWithAliases(localIndexName("test"), Set.empty)),
//            allRemoteIndicesAndAliasesFunc = _ => {
//              Task.now(Set(
//                fullRemoteIndexWithAliases("c01-logs-smg-stats-2020-03-27"),
//                fullRemoteIndexWithAliases("c01-logs-smg-stats-2020-03-28"),
//                fullRemoteIndexWithAliases("c01-logs-smg-stats-2020-03-29")
//              ))
//            }
//          ),
//          found = Set(indexName("*:*-logs-smg-stats-*")),
//        )
//      }
    }
    "not match" when {

    }
  }

  "An IndicesRule for legacy template context" when {
    "getting legacy template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRuleForTemplateRequest(
            configured = NonEmptySet.of(indexNameVar("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation,
            allAllowedIndices = Set(indexName("test*"))
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(indexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and no alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(indexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set.empty
                  )
                ))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and one alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(indexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set(indexName("test1_alias"))
                  )
                ))
            )
          }
        }
        "not match" when {
          "template exists" when {
            "no template is matched" in {
              val existingTemplate1 = LegacyTemplate(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
                aliases = Set.empty
              )
              val existingTemplate2 = LegacyTemplate(
                name = TemplateName("t2"),
                patterns = UniqueNonEmptyList.of(indexPattern("test3")),
                aliases = Set.empty
              )
              val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
              assertNotMatchRuleForTemplateRequest(
                configured = NonEmptySet.of(indexNameVar("test3")),
                requestContext = MockRequestContext
                  .template(gettingTemplateOperation)
                  .addExistingTemplates(existingTemplate1, existingTemplate2),
                specialCause = Some(Cause.TemplateNotFound)
              )
            }
          }
        }
      }
    }
    "adding legacy template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set.empty) should be(Set.empty)
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (without index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (with index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(indexName("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(existingTemplate.name, existingTemplate.patterns, Set.empty)
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(indexName("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test2")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("test1_alias"), indexName("alias_test1"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("{index}_alias"), indexName("alias_{index}"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("test1_alias"), indexName("alias_test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("{index}_alias"), indexName("alias_{index}"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
        }
      }
    }
    "deleting legacy template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("index1"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index3")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1"), indexNameVar("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("index1"), indexName("index2"))
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("b*")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("a*"))
            )
          }
          "all requested existing templates have only allowed indices patterns and aliases" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set(indexName("alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a*")),
              aliases = Set(indexName("balias"))
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("a*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*"), indexPattern("index2*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index11*")),
              aliases = Set(indexName("index11_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index12*")),
              aliases = Set(indexName("alias"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("i*1")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }

  "An IndicesRule for index template context" when {
    "getting index template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRuleForTemplateRequest(
            configured = NonEmptySet.of(indexNameVar("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation,
            allAllowedIndices = Set(indexName("test*")),
            additionalAssertions = blockContext =>
              blockContext.responseTemplateTransformation(
                Set(IndexTemplate(TemplateName("example"), UniqueNonEmptyList.of(indexPattern("test*")), Set.empty))
              ) should be(Set.empty)
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(indexName("alias1"))
            )
            val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(indexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and no alias allowed" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = IndexTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(indexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  IndexTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set.empty
                  )
                ))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and one alias allowed" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = IndexTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(indexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  IndexTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set(indexName("test1_alias"))
                  )
                ))
            )
          }
        }
      }
      "not match" when {
        "template exists" when {
          "no template is matched (because of forbidden indices patterns)" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3")),
              aliases = Set.empty
            )
            val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test3")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              specialCause = Some(Cause.TemplateNotFound)
            )
          }
        }
      }
    }
    "adding index template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set(indexName("alias1"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set.empty) should be(Set.empty)
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (without index placeholder)" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (with index placeholder)" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(indexName("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(existingTemplate.name, existingTemplate.patterns, existingTemplate.aliases)
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (without index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (with index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(indexName("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test2")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("test1_alias"), indexName("alias_test1"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("{index}_alias"), indexName("alias_{index}"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("test1_alias"), indexName("alias_test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(indexName("{index}_alias"), indexName("alias_{index}"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
        }
      }
    }
    "deleting index template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("index1"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set(indexName("alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index3")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1"), indexNameVar("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("index1"), indexName("index2"))
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("b*")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("a*"))
            )
          }
          "all requested existing templates have only allowed indices patterns and aliases" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set(indexName("alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a*")),
              aliases = Set(indexName("balias"))
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("a*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*"), indexPattern("index2*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index11*")),
              aliases = Set(indexName("index11_alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index12*")),
              aliases = Set(indexName("alias"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("i*1")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }

  "An Indices Rule for component template context" when {
    "getting component template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRuleForTemplateRequest(
            configured = NonEmptySet.of(indexNameVar("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation,
            allAllowedIndices = Set(indexName("test*"))
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(indexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one alias allowed" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1_alias"), indexName("test2_alias"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set.empty
            )
            val existingTemplate3 = ComponentTemplate(
              name = TemplateName("d3"),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))),
              allAllowedIndices = Set(indexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1, existingTemplate2)) should be(Set(
                  ComponentTemplate(
                    name = TemplateName("t1"),
                    aliases = Set(indexName("test1_alias"))
                  ),
                  existingTemplate2
                ))
            )
          }
          "all aliases are forbidden" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(indexName("index1")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(
                  ComponentTemplate(
                    name = TemplateName("t1"),
                    aliases = Set.empty
                  )
                ))
            )
          }
        }
      }
    }
    "adding component template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("alias1"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to index name which is used in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("alias1"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("alias1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("alias1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1*"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1"), indexName("test2"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(indexName("test2"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to index name which is used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(existingTemplate.name, existingTemplate.aliases)
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1*"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(indexName("test2*"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1"), indexName("test2"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(indexName("test1"), indexName("test2"), indexName("test3"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(indexName("test*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exit" when {
          "rule allows access to index name which is not used in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(indexName("test2"))
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(indexName("test*"))
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(indexName("test*"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(indexName("test1*"), indexName("index1*"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test2"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(indexName("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(indexName("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(indexName("test*"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("test1*"), indexName("index1*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(indexName("test*"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
        }
      }
    }
    "deleting component template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(indexName("index1"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(indexName("index1"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("*"))
            )
          }
          "all requested existing templates have only allowed aliases" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("index1"), indexName("index2"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(indexName("index3"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1"), indexNameVar("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("index1"), indexName("index2"))
            )
          }
          "all requested existing templates have only allowed aliases patterns" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("a1*"), indexName("a2*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(indexName("b*"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(indexName("a*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(indexName("index1"), indexName("index2"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias pattern which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("index1*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(indexName("index1*"), indexName("index2*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured alias pattern values" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(indexName("i*1"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
      "multi filterable request tries to fetch data for not allowed index" in {
        assertNotMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          indexPacks = Indices.Found(Set(indexName("test2"))) :: Nil
        )
      }
    }
  }

  private def assertMatchRuleForIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                             requestIndices: Set[IndexName],
                                             modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity,
                                             found: Set[IndexName] = Set.empty) =
    assertRuleForIndexRequest(configured, requestIndices, isMatched = true, modifyRequestContext, found)

  private def assertNotMatchRuleForIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                requestIndices: Set[IndexName],
                                                modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity) =
    assertRuleForIndexRequest(configured, requestIndices, isMatched = false, modifyRequestContext, Set.empty)

  private def assertRuleForIndexRequest(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                        requestIndices: Set[IndexName],
                                        isMatched: Boolean,
                                        modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext,
                                        found: Set[IndexName]) = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = modifyRequestContext apply MockRequestContext.indices
      .copy(
        filteredIndices = requestIndices,
        action = Action("indices:data/read/search"),
        isReadOnlyRequest = true,
        hasRemoteClusters = true,
        allIndicesAndAliases = Set(
          IndexWithAliases(localIndexName("test1"), Set.empty),
          IndexWithAliases(localIndexName("test2"), Set.empty),
          IndexWithAliases(localIndexName("test3"), Set.empty),
          IndexWithAliases(localIndexName("test4"), Set.empty),
          IndexWithAliases(localIndexName("test5"), Set.empty)
        )
      )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      List.empty,
      requestIndices,
      Set.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(GeneralIndexRequestBlockContext(
        requestContext,
        UserMetadata.from(requestContext),
        Set.empty,
        List.empty,
        found,
        configuredValues
          .toNonEmptyList.toList
          .collect { case a: AlreadyResolved[IndexName] => a }
          .flatMap(_.value.toList)
          .toSet
      ))
      else Rejected(Some(Cause.IndexNotFound))
    }
  }

  private def assertMatchRuleForMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                  indexPacks: List[Indices],
                                                  modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity,
                                                  allowed: List[Indices]) = {
    assertRuleForMultiForIndexRequest(configured, indexPacks, isMatched = true, modifyRequestContext, allowed)
  }

  private def assertNotMatchRuleForMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                     indexPacks: List[Indices],
                                                     modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity) = {
    assertRuleForMultiForIndexRequest(configured, indexPacks, isMatched = false, modifyRequestContext, List.empty)
  }

  private def assertRuleForMultiForIndexRequest(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                indexPacks: List[Indices],
                                                isMatched: Boolean,
                                                modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext,
                                                allowed: List[Indices]) = {
    val rule = new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = RandomBasedUniqueIdentifierGenerator
    )
    val requestContext = modifyRequestContext apply MockRequestContext.filterableMulti
      .copy(
        indexPacks = indexPacks,
        action = Action("indices:data/read/mget"),
        isReadOnlyRequest = true,
        method = Method("POST"),
        allIndicesAndAliases = Set(
          IndexWithAliases(localIndexName("test1"), Set.empty),
          IndexWithAliases(localIndexName("test2"), Set.empty),
          IndexWithAliases(localIndexName("test3"), Set.empty),
          IndexWithAliases(localIndexName("test4"), Set.empty),
          IndexWithAliases(localIndexName("test5"), Set.empty)
        )
      )
    val blockContext = FilterableMultiRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      List.empty,
      indexPacks,
      None
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(FilterableMultiRequestBlockContext(
        requestContext,
        UserMetadata.from(requestContext),
        Set.empty,
        List.empty,
        allowed,
        None
      ))
      else Rejected(Some(Cause.IndexNotFound))
    }
  }

  private def assertMatchRuleForTemplateRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                requestContext: MockTemplateRequestContext,
                                                templateOperationAfterProcessing: TemplateOperation,
                                                allAllowedIndices: Set[IndexName] = Set.empty,
                                                additionalAssertions: TemplateRequestBlockContext => Assertion = noTransformation): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.right.get
    ruleResult should matchPattern {
      case Fulfilled(blockContext@TemplateRequestBlockContext(rc, metadata, headers, Nil, operation, _, allowedIndices))
        if rc == requestContext
          && metadata == requestContext.initialBlockContext.userMetadata
          && headers.isEmpty
          && operation == templateOperationAfterProcessing
          && allowedIndices == allAllowedIndices
          && additionalAssertions(blockContext) == Succeeded =>
    }
  }

  private def assertNotMatchRuleForTemplateRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                   requestContext: MockTemplateRequestContext,
                                                   specialCause: Option[Cause] = None): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.right.get
    ruleResult shouldBe Rejected(specialCause)
  }

  private def createIndicesRule(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]]) = {
    new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = (_: Refined[Int, Positive]) => "0000000000"
    )
  }

  private def indexNameVar(value: NonEmptyString): RuntimeMultiResolvableVariable[IndexName] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(value)(AlwaysRightConvertible.from(indexName))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

  private implicit class MockTemplateRequestContextOps(underlying: MockTemplateRequestContext) {
    def addExistingTemplates(template: Template, otherTemplates: Template*): MockTemplateRequestContext = {
      underlying.copy(allTemplates = underlying.allTemplates + template ++ otherTemplates.toSet)
    }
  }

  private def noTransformation(blockContext: TemplateRequestBlockContext) = {
    // we check here if sth else than identity was configured
    val controlTemplates: Set[Template] = Set(
      LegacyTemplate(TemplateName("whatever1"), UniqueNonEmptyList.of(indexPattern("*")), Set(indexName("alias"))),
      IndexTemplate(TemplateName("whatever2"), UniqueNonEmptyList.of(indexPattern("*")), Set(indexName("alias"))),
      ComponentTemplate(TemplateName("whatever3"), Set(indexName("alias"))),
    )
    blockContext.responseTemplateTransformation(controlTemplates) should be(controlTemplates)
  }

  private def fullRemoteIndexWithAliases(fullRemoteIndexName: String, remoteIndexAliases: String*) = {
    def fullRemoteIndexNameFrom(value: String) = {
      Remote.fromString(value) match {
        case Some(remote: Remote.Full) => remote
        case _ =>
          throw new IllegalStateException(s"cannot create full remote index name from '$fullRemoteIndexName'")
      }
    }
    FullRemoteIndexWithAliases(
      fullRemoteIndexNameFrom(fullRemoteIndexName),
      remoteIndexAliases.toSet.map(fullRemoteIndexNameFrom)
    )
  }
}
