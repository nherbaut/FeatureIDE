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
package de.ovgu.featureide.fm.ui.editors.configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import de.ovgu.featureide.fm.core.FMCorePlugin;
import de.ovgu.featureide.fm.core.Logger;
import de.ovgu.featureide.fm.core.ModelMarkerHandler;
import de.ovgu.featureide.fm.core.base.IFeatureModel;
import de.ovgu.featureide.fm.core.base.event.FeatureIDEEvent;
import de.ovgu.featureide.fm.core.base.event.IEventListener;
import de.ovgu.featureide.fm.core.color.FeatureColorManager;
import de.ovgu.featureide.fm.core.configuration.Configuration;
import de.ovgu.featureide.fm.core.configuration.Selection;
import de.ovgu.featureide.fm.core.io.EclipseFileSystem;
import de.ovgu.featureide.fm.core.io.Problem;
import de.ovgu.featureide.fm.core.io.ProblemList;
import de.ovgu.featureide.fm.core.io.manager.ConfigurationManager;
import de.ovgu.featureide.fm.core.io.manager.FeatureModelManager;
import de.ovgu.featureide.fm.core.job.JobStartingStrategy;
import de.ovgu.featureide.fm.core.job.JobToken;
import de.ovgu.featureide.fm.core.job.LongRunningWrapper;
import de.ovgu.featureide.fm.ui.FMUIPlugin;
import de.ovgu.featureide.fm.ui.editors.ConfigurationEditorErrorPage;
import de.ovgu.featureide.fm.ui.editors.featuremodel.GUIDefaults;

/**
 * Displays a configuration file.
 *
 * @author Constanze Adler
 * @author Christian Becker
 * @author Jens Meinicke
 * @author Hannes Smurawsky
 */
public class ConfigurationEditor extends MultiPageEditorPart implements GUIDefaults, IEventListener, IResourceChangeListener, IConfigurationEditor {

	public static final String ID = FMUIPlugin.PLUGIN_ID + ".editors.configuration.ConfigurationEditor";

	private final JobToken configJobToken = LongRunningWrapper.createToken(JobStartingStrategy.CANCEL_WAIT_ONE);

	private final List<IConfigurationEditorPage> allPages = new ArrayList<>(5);
	private List<IConfigurationEditorPage> extensionPages;
	private List<IConfigurationEditorPage> internalPages;
	private TextEditorPage textEditorPage;

	private ModelMarkerHandler<IFile> markerHandler;

	/**
	 * File manager for the given configuration.
	 */
	private ConfigurationManager configurationManager;
	/**
	 * Manager for the feature model that is configured by the configuration in <code>configurationManager</code>.
	 */
	private FeatureModelManager featureModelManager;

	/**
	 * The expansion mode when selecting features in a configuration.
	 */
	private ExpandAlgorithm currentExpandAlgorithm;

	private int currentPageIndex = -1;

	private boolean autoSelectFeatures = true;
	private boolean invalidFeatureModel = true;
	private boolean readConfigurationError = false;
	private boolean readFeatureModelError = false;

	@Override
	public void dispose() {
		super.dispose();
		LongRunningWrapper.cancelAllJobs(configJobToken);
		if (featureModelManager != null) {
			featureModelManager.removeListener(ConfigurationEditor.this);
		}
		if (configurationManager != null) {
			configurationManager.removeListener(ConfigurationEditor.this);
			configurationManager.overwrite();
		}
		FeatureColorManager.removeListener(ConfigurationEditor.this);
	}

	public List<IConfigurationEditorPage> getExtensionPages() {
		return extensionPages;
	}

	@Override
	public ExpandAlgorithm getExpandAlgorithm() {
		return currentExpandAlgorithm;
	}

	@Override
	public ExpandAlgorithm readExpansionAlgorithm() {
		final IProject project = EclipseFileSystem.getResource(featureModelManager.getObject().getSourceFile()).getProject();
		final ScopedPreferenceStore store = new ScopedPreferenceStore(new ProjectScope(project), FMUIPlugin.getDefault().getID());
		final int index = store.getInt(EXPAND_PREFERENCE);
		try {
			return ExpandAlgorithm.values()[index];
		} catch (final Exception e) {
			Logger.logWarning("Failed to read the configuration expansion algorithm for project " + project.getName() + ", falling back to default.");
			return ExpandAlgorithm.NONE;
		}
	}

	@Override
	public void setExpandAlgorithm(ExpandAlgorithm expandAlgorithm) {
		currentExpandAlgorithm = expandAlgorithm;
	}

	@Override
	public void saveExpansionAlgorithm() {
		final int index = currentExpandAlgorithm.ordinal();
		final IProject project = EclipseFileSystem.getResource(featureModelManager.getObject().getSourceFile()).getProject();
		final ScopedPreferenceStore store = new ScopedPreferenceStore(new ProjectScope(project), FMUIPlugin.getDefault().getID());
		store.setValue(EXPAND_PREFERENCE, index);
		try {
			store.save();
		} catch (final IOException e1) {
			Logger.logWarning("Failed to save the configuration expansion algorithm for project " + project.getName());
		}
	}

	@Override
	protected void setInput(IEditorInput input) {
		// Cast is necessary for backward compatibility, don't remove
		final IFile file = (IFile) input.getAdapter(IFile.class);
		markerHandler = new ModelMarkerHandler<>(file);

		ResourcesPlugin.getWorkspace().addResourceChangeListener(this);
		FeatureColorManager.addListener(this);
		super.setInput(input);
		setPartName(file.getName());

		final Path modelPath = setFeatureModelFile(file.getProject());

		if (!isReadFeatureModelError()) {
			featureModelManager = FeatureModelManager.getInstance(modelPath);
			if (featureModelManager != null) {
				invalidFeatureModel = featureModelManager.getLastProblems().containsError();
				if (invalidFeatureModel) {
					return;
				}
				currentExpandAlgorithm = readExpansionAlgorithm();
			} else {
				setReadFeatureModelError(true);
			}
		}

		configurationManager = ConfigurationManager.getInstance(EclipseFileSystem.getPath(file));
		if (configurationManager != null) {
			if (!isReadFeatureModelError()) {
				configurationManager.linkFeatureModel(featureModelManager);
			}

			final ProblemList lastProblems = configurationManager.getLastProblems();
			createModelFileMarkers(lastProblems);
			setReadConfigurationError(lastProblems.containsError());

			if (!isReadFeatureModelError()) {
				configurationManager.update();
				featureModelManager.addListener(this);
			}
			configurationManager.addListener(this);
			firePropertyChange(IEditorPart.PROP_DIRTY);
		} else {
			setReadConfigurationError(true);
			return;
		}

		if (!isIOError()) {
			final IConfigurationEditorPage page = getPage(getActivePage());
			if (page != null) {
				page.propertyChange(null);
			}
		}
	}

	private Path setFeatureModelFile(IProject project) {
		final IFile modelFile = FMUIPlugin.findModelFile(project).orElse(null);
		setReadFeatureModelError(modelFile == null);
		return modelFile == null ? null : Paths.get(modelFile.getLocation().toOSString());
	}

	@Override
	public void propertyChange(final FeatureIDEEvent evt) {
		switch (evt.getEventType()) {
		case MODEL_DATA_SAVED:
		case MODEL_DATA_OVERWRITTEN:
		case FEATURE_COLOR_CHANGED:
			if (evt.getSource() instanceof IFeatureModel) {
				configurationManager.update();
				if (configurationManager.hasChanged()) {
					firePropertyChange(IEditorPart.PROP_DIRTY);
				}
				setReadConfigurationError(false);

				// Reinitialize the pages
				final IConfigurationEditorPage currentPage = getPage(currentPageIndex);
				if (currentPage != null) {
					currentPage.propertyChange(evt);
				}
			} else if (evt.getSource() instanceof Configuration) {
				// Reinitialize the pages
				final IConfigurationEditorPage currentPage = getPage(currentPageIndex);
				if (currentPage != null) {
					currentPage.propertyChange(evt);
				}
			}
			break;
		case CONFIGURABLE_ATTRIBUTE_CHANGED:
			if (!Objects.equals(evt.getOldValue(), evt.getNewValue())) {
				final IConfigurationEditorPage currentPage = getPage(currentPageIndex);
				if (currentPage != null) {
					currentPage.propertyChange(evt);
				}
			}

		default:
			break;
		}
	}

	@Override
	public void setFocus() {
		if (internalPages.get(0) instanceof ConfigurationPage) {
			((ConfigurationPage) internalPages.get(0)).tree.setFocus();
		}
	}

	@Override
	protected void createPages() {
		if (isReadConfigurationError()) {
			internalPages = new ArrayList<>(1);
			if (isReadFeatureModelError()) {
				internalPages.add((ConfigurationEditorErrorPage) initPage(new ConfigurationEditorErrorPage()));
			} else {
				textEditorPage = (TextEditorPage) initPage(new TextEditorPage());
				internalPages.add(textEditorPage);
			}
		} else {
			internalPages = new ArrayList<>(3);
			internalPages.add(initPage(new ConfigurationPage()));
			internalPages.add(initPage(new AdvancedConfigurationPage()));
			textEditorPage = (TextEditorPage) initPage(new TextEditorPage());
			internalPages.add(textEditorPage);
		}
		allPages.addAll(internalPages);

		final IConfigurationElement[] config = Platform.getExtensionRegistry().getConfigurationElementsFor(FMUIPlugin.PLUGIN_ID + ".ConfigurationEditor");
		extensionPages = new ArrayList<>(config.length);
		try {
			for (final IConfigurationElement e : config) {
				final Object o = e.createExecutableExtension("class");
				if (o instanceof IConfigurationEditorPage) {
					extensionPages.add(initPage((IConfigurationEditorPage) o));
				}
			}
		} catch (final Exception e) {
			FMCorePlugin.getDefault().logError(e);
		}
		allPages.addAll(extensionPages);

		if (isIOError()) {
			setActivePage(0);
		} else if (requiresAdvancedConfigurationPage()) {
			setActivePage(internalPages.get(1).getIndex());
		}

		for (final IConfigurationEditorPage externalPage : extensionPages) {
			externalPage.propertyChange(null);
		}
	}

	private boolean requiresAdvancedConfigurationPage() {
		return (configurationManager != null) && //
			configurationManager.getSnapshot().getFeatures().parallelStream() //
					.anyMatch(f -> f.getManual() == Selection.UNSELECTED);
	}

	private IConfigurationEditorPage initPage(IConfigurationEditorPage page) {
		page = page.getPage();
		page.setConfigurationEditor(this);
		try {
			page.setIndex(addPage(page, getEditorInput()));
			setPageText(page.getIndex(), page.getPageText());
		} catch (final PartInitException e) {
			FMUIPlugin.getDefault().logError(e);
		}
		return page;
	}

	@Override
	protected void pageChange(int newPageIndex) {
		if (newPageIndex != currentPageIndex) {
			final IConfigurationEditorPage currentPage = getPage(currentPageIndex);
			final IConfigurationEditorPage newPage = getPage(newPageIndex);
			if (currentPage != null) {
				if (currentPage.allowPageChange(newPageIndex)) {
					currentPage.pageChangeFrom(newPageIndex);
				} else {
					setActivePage(currentPageIndex);
					return;
				}
			}
			if (newPage != null) {
				newPage.pageChangeTo(newPageIndex);
			}
			currentPageIndex = newPageIndex;
			super.pageChange(newPageIndex);
		}
	}

	private IConfigurationEditorPage getPage(int pageIndex) {
		if (pageIndex >= 0) {
			for (final IConfigurationEditorPage internalPage : allPages) {
				if (internalPage.getIndex() == pageIndex) {
					return internalPage;
				}
			}
		}
		return null;
	}

	@Override
	public void doSave(final IProgressMonitor monitor) {
		final IConfigurationEditorPage currentPage = getPage(currentPageIndex);
		if (currentPage != null) {
			if (currentPage.getID() == TextEditorPage.ID) {
				if (configurationManager == null) {
					currentPage.doSave(monitor);
				} else {
					// TODO fix for #1082. works but might be possible with less effort
					final IAnnotationModel model = textEditorPage.getDocumentProvider().getAnnotationModel(textEditorPage.getEditorInput());
					final Iterator<Annotation> annotationIterator = model.getAnnotationIterator();
					final List<Annotation> annotationList = new ArrayList<>();
					annotationIterator.forEachRemaining(annotationList::add);
					annotationList.forEach(model::removeAnnotation);

					final ProblemList lastProblems = textEditorPage.updateConfiguration();
					configurationManager.externalSave(() -> notifyPages(monitor, currentPage));
					createModelFileMarkers(lastProblems);
					setReadConfigurationError(lastProblems.containsError());
				}
			} else {
				configurationManager.save();
				notifyPages(monitor, currentPage);
			}
		} else {
			for (final IConfigurationEditorPage internalPage : allPages) {
				internalPage.doSave(monitor);
			}
		}
	}

	private void notifyPages(final IProgressMonitor monitor, final IConfigurationEditorPage currentPage) {
		currentPage.doSave(monitor);
		final Configuration configuration = configurationManager.getSnapshot();
		for (final IConfigurationEditorPage internalPage : allPages) {
			if (internalPage != currentPage) {
				internalPage.propertyChange(new FeatureIDEEvent(configuration, FeatureIDEEvent.EventType.MODEL_DATA_SAVED));
			}
		}
	}

	@Override
	public void doSaveAs() {}

	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}

	@Override
	public void resourceChanged(final IResourceChangeEvent event) {
		if (event.getResource() == null) {
			return;
		}

		final IEditorInput input = getEditorInput();
		if (input instanceof IFileEditorInput) {
			final IFile inputFile = ((IFileEditorInput) input).getFile();

			// Closes editor if resource is deleted
			if ((event.getType() == IResourceChangeEvent.POST_CHANGE) && (event.getResource().getType() == IResource.PROJECT)) {
				final IResourceDelta inputFileDelta = event.getDelta().findMember(inputFile.getFullPath());
				if ((inputFileDelta != null) && ((inputFileDelta.getFlags() & IResourceDelta.REMOVED) == 0)) {
					closeEditor(input);
				}
			} else if ((event.getType() == IResourceChangeEvent.PRE_CLOSE) && inputFile.getProject().equals(event.getResource())) {
				closeEditor(input);
			}
		}
	}

	private void closeEditor(final IEditorInput input) {
		Display.getDefault().asyncExec(new Runnable() {

			@Override
			public void run() {
				if ((getSite() != null) && (getSite().getWorkbenchWindow() != null)) {
					for (final IWorkbenchPage page : getSite().getWorkbenchWindow().getPages()) {
						page.closeEditor(page.findEditor(input), true);
					}
				}
			}
		});
	}

	@Override
	public boolean isAutoSelectFeatures() {
		return autoSelectFeatures;
	}

	@Override
	public void setAutoSelectFeatures(boolean autoSelectFeatures) {
		this.autoSelectFeatures = autoSelectFeatures;
	}

	@Override
	public boolean hasValidFeatureModel() {
		return !invalidFeatureModel;
	}

	void createModelFileMarkers(List<Problem> warnings) {
		markerHandler.deleteAllModelMarkers();
		for (final Problem warning : warnings) {
			markerHandler.createModelMarker(warning.message, warning.severity.getLevel(), warning.line);
		}
	}

	@Override
	public boolean isIOError() {
		return readConfigurationError || readFeatureModelError;
	}

	@Override
	public boolean isReadConfigurationError() {
		return readConfigurationError;
	}

	@Override
	public boolean isReadFeatureModelError() {
		return readFeatureModelError;
	}

	private void setReadFeatureModelError(boolean readFeatureModelError) {
		this.readFeatureModelError = readFeatureModelError;
	}

	void setReadConfigurationError(boolean readConfigurationError) {
		this.readConfigurationError = readConfigurationError;
	}

	@Override
	public ConfigurationManager getConfigurationManager() {
		return configurationManager;
	}

	@Override
	public FeatureModelManager getFeatureModelManager() {
		return featureModelManager;
	}

}
