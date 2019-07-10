/* FeatureIDE - A Framework for Feature-Oriented Software Development
 * Copyright (C) 2005-2019  FeatureIDE team, University of Magdeburg, Germany
 *
 * This file is part of FeatureIDE.
 *
 * FeatureIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FeatureIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FeatureIDE.  If not, see <http://www.gnu.org/licenses/>.
 *
 * See http://featureide.cs.ovgu.de/ for further information.
 */
package de.ovgu.featureide.fm.core.analysis.cnf.generator.configuration.twise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import de.ovgu.featureide.fm.core.analysis.cnf.ClauseList;
import de.ovgu.featureide.fm.core.analysis.cnf.LiteralSet;

/**
 *
 * @author Sebastian Krieter
 */
class PresenceConditionManager {

	private final List<List<PresenceCondition>> dictonary = new ArrayList<>();
	private final List<List<PresenceCondition>> groupedPresenceConditions = new ArrayList<>();

	public PresenceConditionManager(TWiseConfigurationUtil util, List<List<ClauseList>> expressions) {
		final LiteralSet coreDeadFeature = util.getDeadCoreFeatures();
		final int numberOfVariables = util.getCnf().getVariables().size();

		final HashMap<PresenceCondition, PresenceCondition> presenceConditionSet = new HashMap<>();

		dictonary.add(null);
		for (int i = 0; i < numberOfVariables; i++) {
			dictonary.add(new ArrayList<PresenceCondition>());
			dictonary.add(new ArrayList<PresenceCondition>());
		}

		int groupIndex = 0;
		for (final List<ClauseList> group : expressions) {
			final List<PresenceCondition> newNodeList = new ArrayList<>();
			expressionLoop: for (final ClauseList clauses : group) {
				final List<LiteralSet> newClauses = new ArrayList<>();
				for (final LiteralSet clause : clauses) {
					// If clause can be satisfied
					if ((clause.countConflicts(coreDeadFeature) == 0)) {
						// If clause is already satisfied
						if (coreDeadFeature.containsAll(clause)) {
							continue expressionLoop;
						} else {
							newClauses.add(clause.clone());
						}
					}
				}
				if (!newClauses.isEmpty()) {
					final PresenceCondition pc = new PresenceCondition(new ClauseList(newClauses));
					PresenceCondition mappedPc = presenceConditionSet.get(pc);
					if (mappedPc == null) {
						mappedPc = pc;
						presenceConditionSet.put(mappedPc, mappedPc);

						for (final LiteralSet literalSet : mappedPc) {
							for (final int literal : literalSet.getLiterals()) {
								final int dictionaryIndex = literal < 0 ? numberOfVariables - literal : literal;
								dictonary.get(dictionaryIndex).add(mappedPc);
							}
						}
					}
					mappedPc.addGroup(groupIndex);
					Collections.sort(mappedPc, new Comparator<LiteralSet>() {
						@Override
						public int compare(LiteralSet o1, LiteralSet o2) {
							return o1.size() - o2.size();
						}
					});
					newNodeList.add(mappedPc);
				}
			}
			groupedPresenceConditions.add(newNodeList);
			groupIndex++;
		}

		for (final List<PresenceCondition> list : groupedPresenceConditions) {
			final Comparator<PresenceCondition> comparator = new Comparator<PresenceCondition>() {
				@Override
				public int compare(PresenceCondition o1, PresenceCondition o2) {
					final int clauseCountDiff = o1.size() - o2.size();
					if (clauseCountDiff != 0) {
						return clauseCountDiff;
					}
					int clauseLengthDiff = 0;
					for (int i = 0; i < o1.size(); i++) {
						clauseLengthDiff += o2.get(i).size() - o1.get(i).size();
					}
					return clauseLengthDiff;
				}
			};
			Collections.sort(list, comparator);
		}
	}

	public List<List<PresenceCondition>> getDictonary() {
		return dictonary;
	}

	public List<List<PresenceCondition>> getGroupedPresenceConditions() {
		return groupedPresenceConditions;
	}

}
