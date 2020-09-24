/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.idp

import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.QueryPlannerKit
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.plans.LogicalPlan

case object OptionalMatchConnector
  extends ComponentConnector {

  def solverStep(goalBitAllocation: GoalBitAllocation, queryGraph: QueryGraph, interestingOrder: InterestingOrder, kit: QueryPlannerKit): ComponentConnectorSolverStep =
    (registry: IdRegistry[QueryGraph], goal: Goal, table: IDPCache[LogicalPlan], context: LogicalPlanningContext) => {
      val optionalsGoal = goalBitAllocation.optionalMatchesGoal(goal)
      for {
        id <- optionalsGoal.bitSet.toIterator
        optionalQg <- registry.lookup(id).toIterator
        leftGoal = Goal(goal.bitSet - id)
        leftPlan <- table(leftGoal).iterator
        canPlan = optionalQg.argumentIds subsetOf leftPlan.availableSymbols
        if canPlan
        optionalSolver <- context.config.optionalSolvers
        bestPlans <- optionalSolver(optionalQg, leftPlan, interestingOrder, context).toIterator
        plan <- bestPlans.allResults
      } yield plan
    }
}
