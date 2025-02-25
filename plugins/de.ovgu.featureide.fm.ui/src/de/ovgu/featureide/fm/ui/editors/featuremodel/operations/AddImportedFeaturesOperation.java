/* FeatureIDE - A Framework for Feature-Oriented Software Development
 * Copyright (C) 2005-2017  FeatureIDE team, University of Magdeburg, Germany
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
package de.ovgu.featureide.fm.ui.editors.featuremodel.operations;

import static de.ovgu.featureide.fm.core.localization.StringTable.ADD_IMPORTED_FEATURES;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.ovgu.featureide.fm.core.base.IConstraint;
import de.ovgu.featureide.fm.core.base.IFeature;
import de.ovgu.featureide.fm.core.base.IFeatureModel;
import de.ovgu.featureide.fm.core.base.IFeatureModelFactory;
import de.ovgu.featureide.fm.core.base.IFeatureStructure;
import de.ovgu.featureide.fm.core.base.event.FeatureIDEEvent;
import de.ovgu.featureide.fm.core.base.event.FeatureIDEEvent.EventType;
import de.ovgu.featureide.fm.core.base.impl.FMFactoryManager;
import de.ovgu.featureide.fm.core.base.impl.MultiConstraint;
import de.ovgu.featureide.fm.core.base.impl.MultiFeature;
import de.ovgu.featureide.fm.core.base.impl.MultiFeatureModel;
import de.ovgu.featureide.fm.core.base.impl.RootFeatureSet;
import de.ovgu.featureide.fm.core.io.manager.FeatureModelManager;
import de.ovgu.featureide.fm.core.io.manager.IFeatureModelManager;

/**
 * Operation to add imported features to the feature model. Enables undo/redo functionality.
 *
 * @author Kevin Jedelhauser
 * @author Johannes Herschel
 */
public class AddImportedFeaturesOperation extends AbstractFeatureModelOperation {

	/**
	 * Name of the feature below which the imported features are added.
	 */
	private final String parentFeatureName;
	/**
	 * The features to be imported.
	 */
	private final Map<MultiFeatureModel.UsedModel, Set<RootFeatureSet>> importedFeatures;

	/**
	 * Ids of the newly added features.
	 */
	private final List<Long> clonedImportedFeatureIds;
	/**
	 * Ids of the newly added constraints.
	 */
	private final List<Long> clonedImportedConstraintIds;

	public AddImportedFeaturesOperation(IFeatureModelManager featureModelManager, IFeature parentFeature,
			Map<MultiFeatureModel.UsedModel, Set<RootFeatureSet>> importedFeatures) {
		super(featureModelManager, ADD_IMPORTED_FEATURES);
		parentFeatureName = parentFeature.getName();
		this.importedFeatures = importedFeatures;

		clonedImportedFeatureIds = new ArrayList<>();
		clonedImportedConstraintIds = new ArrayList<>();
	}

	@Override
	protected FeatureIDEEvent operation(IFeatureModel featureModel) {
		// Clear ids in case of a redo
		clonedImportedFeatureIds.clear();
		clonedImportedConstraintIds.clear();

		final IFeature parentFeature = featureModel.getFeature(parentFeatureName);
		if (parentFeature != null) {
			final IFeatureModelFactory factory = FMFactoryManager.getInstance().getFactory(featureModel);
			for (final Map.Entry<MultiFeatureModel.UsedModel, Set<RootFeatureSet>> entry : importedFeatures.entrySet()) {
				final String featurePrefix = entry.getKey().getVarName() + ".";
				for (final RootFeatureSet rs : entry.getValue()) {
					// Add features
					for (final IFeature feature : rs.getRootFeatures()) {
						final IFeatureStructure clonedFeature = cloneFeature(feature.getStructure(), featurePrefix, featureModel, factory);
						clonedFeature.setMandatory(false); // Necessary for models with multiple root features
						clonedImportedFeatureIds.add(clonedFeature.getFeature().getInternalId());
						parentFeature.getStructure().addChild(clonedFeature);
					}

					// Add constraints
					for (final IConstraint constraint : rs.getConstraints()) {
						final IConstraint clonedConstraint = cloneConstraint(constraint, featurePrefix, featureModel, factory);
						clonedImportedConstraintIds.add(clonedConstraint.getInternalId());
						featureModel.addConstraint(clonedConstraint);
					}
				}
			}
		}

		return FeatureIDEEvent.getDefault(EventType.STRUCTURE_CHANGED);
	}

	@Override
	protected FeatureIDEEvent inverseOperation(IFeatureModel featureModel) {
		// Remove constraints
		for (final long id : clonedImportedConstraintIds) {
			final IConstraint constraint = (IConstraint) featureModel.getElement(id);
			featureModel.removeConstraint(constraint);
		}

		// Remove features
		for (final long id : clonedImportedFeatureIds) {
			final IFeature feature = (IFeature) featureModel.getElement(id);
			feature.getStructure().getParent().removeChild(feature.getStructure());
			removeSubtree(feature.getStructure(), featureModel);
		}

		return FeatureIDEEvent.getDefault(EventType.STRUCTURE_CHANGED);
	}

	@Override
	protected int getChangeIndicator() {
		return FeatureModelManager.CHANGE_DEPENDENCIES;
	}

	/**
	 * Clones the feature tree given by <code>featureStructure</code> to be used in the given feature model.
	 *
	 * @param featureStructure Root of the subtree to be cloned
	 * @param featurePrefix The prefix for the imported features
	 * @param featureModel The feature model to contain the cloned subtree
	 * @param factory The feature model factory of <code>featureModel</code>
	 * @return The cloned subtree
	 */
	private IFeatureStructure cloneFeature(IFeatureStructure featureStructure, String featurePrefix, IFeatureModel featureModel, IFeatureModelFactory factory) {
		final IFeature clonedFeature = factory.copyFeature(featureModel, featureStructure.getFeature(), false);
		clonedFeature.setName(featurePrefix + clonedFeature.getName());
		((MultiFeature) clonedFeature).setType(MultiFeature.TYPE_INTERFACE);

		featureModel.addFeature(clonedFeature);

		for (final IFeatureStructure child : featureStructure.getChildren()) {
			clonedFeature.getStructure().addChild(cloneFeature(child, featurePrefix, featureModel, factory));
		}

		return clonedFeature.getStructure();
	}

	/**
	 * Clones the given constraint to be used in the given feature model.
	 *
	 * @param constraint The constraint to be cloned
	 * @param featurePrefix The prefix for the features of the imported constraint
	 * @param featureModel The feature model to contain the cloned constraint
	 * @param factory The feature model factory of <code>featureModel</code>
	 * @return The cloned constraint
	 */
	private IConstraint cloneConstraint(IConstraint constraint, String featurePrefix, IFeatureModel featureModel, IFeatureModelFactory factory) {
		// Clone first time so feature name changes are not applied to original constraint
		final IConstraint clonedConstraint = factory.copyConstraint(featureModel, constraint, false);
		clonedConstraint.getNode().modifyFeatureNames(featureName -> featurePrefix + featureName);
		((MultiConstraint) clonedConstraint).setType(MultiFeature.TYPE_INTERFACE);
		// Clone second time to update feature references of the constraint
		return clonedConstraint.clone(featureModel);
	}

	/**
	 * Removes all features of the given subtree from the given feature model.
	 *
	 * @param featureStructure The subtree to remove
	 * @param featureModel The model from which to remove the features
	 */
	private void removeSubtree(IFeatureStructure featureStructure, IFeatureModel featureModel) {
		featureModel.deleteFeatureFromTable(featureStructure.getFeature());
		for (final IFeatureStructure child : featureStructure.getChildren()) {
			removeSubtree(child, featureModel);
		}
	}
}
