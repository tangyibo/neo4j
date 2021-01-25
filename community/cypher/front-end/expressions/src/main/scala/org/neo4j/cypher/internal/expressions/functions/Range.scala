/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.expressions.functions

import org.neo4j.cypher.internal.expressions.FunctionTypeSignature
import org.neo4j.cypher.internal.expressions.TypeSignatures
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTList

case object Range extends Function with TypeSignatures {
  def name = "range"

  override val signatures = Vector(
    FunctionTypeSignature(functionName = name, names = Vector("start", "end"), argumentTypes = Vector(CTInteger, CTInteger),
      outputType = CTList(CTInteger), description = "Returns a list comprising all integer values within a specified range.", category = Category.LIST),
    FunctionTypeSignature(functionName = name, names = Vector("start", "end", "step"), argumentTypes = Vector(CTInteger, CTInteger, CTInteger),
      outputType = CTList(CTInteger), description = "Returns a list comprising all integer values within a specified range created with step length.", category = Category.LIST)
  )
}
