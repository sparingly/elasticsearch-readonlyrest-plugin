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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.rules.ApiKeysRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.domain.ApiKey
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers

object ApiKeysRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[ApiKeysRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[ApiKeysRule]] = {
    DecoderHelpers
      .decodeNonEmptyStringLikeOrNonEmptySet(ApiKey.apply)
      .map(apiKeys => RuleWithVariableUsageDefinition.create(new ApiKeysRule(ApiKeysRule.Settings(apiKeys))))
  }
}

