/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Red Hat Inc. - created based on MoveResourceChange and RenameResourceChange
 *******************************************************************************/
package org.eclipse.ltk.core.refactoring.resource;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.ChangeDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.internal.core.refactoring.BasicElementLabels;
import org.eclipse.ltk.internal.core.refactoring.Messages;
import org.eclipse.ltk.internal.core.refactoring.RefactoringCoreMessages;

/**
 * {@link Change} that moves and renames a resource.
 *
 * @since 3.10
 */
public class MoveRenameResourceChange extends ResourceChange {

	private final IResource fSource;
	private final String fNewName;
	private final IContainer fTarget;
	private final long fStampToRestore;
	private final Change fRestoreSourceChange;

	private ChangeDescriptor fDescriptor;

	/**
	 * Creates the change.
	 *
	 * @param source the resource to move
	 * @param target the container the resource is moved to. An existing resource at the destination will be
	 * replaced.
	 * @param newName new name of resource
	 */
	public MoveRenameResourceChange(IResource source, IContainer target, String newName) {
		this(source, target, newName, IResource.NULL_STAMP, null);
	}

	/**
	 * Creates the change.
	 *
	 * @param source the resource to move
	 * @param target the container the resource is moved to. An existing resource at the destination will be
	 * replaced.
	 * @param newName the new name of the resource in the the target container
	 * @param stampToRestore the stamp to restore on the moved resource
	 * 	@param restoreSourceChange the change to restore a resource at the source or <code>null</code> if no resource
	 * needs to be resourced.
	 */
	protected MoveRenameResourceChange(IResource source, IContainer target, String newName, long stampToRestore, Change restoreSourceChange) {
		fSource= source;
		fTarget= target;
		fNewName= newName;
		fStampToRestore= stampToRestore;
		fRestoreSourceChange= restoreSourceChange;

		// We already present a dialog to the user if he
		// moves read-only resources. Since moving a resource
		// doesn't do a validate edit (it actually doesn't
		// change the content we can't check for READ only
		// here.
		setValidationMethod(VALIDATE_NOT_DIRTY);
	}

	@Override
	public ChangeDescriptor getDescriptor() {
		return fDescriptor;
	}

	/**
	 * Sets the change descriptor to be returned by {@link Change#getDescriptor()}.
	 *
	 * @param descriptor the change descriptor
	 */
	public void setDescriptor(ChangeDescriptor descriptor) {
		fDescriptor= descriptor;
	}

	@Override
	public final Change perform(IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		SubMonitor subMonitor = SubMonitor.convert(monitor, getName(), 4);


		Change deleteUndo= null;

		// delete destination if required
		IResource resourceAtDestination= fTarget.findMember(fNewName);
		if (resourceAtDestination != null && resourceAtDestination.exists()) {
			deleteUndo= performDestinationDelete(resourceAtDestination, subMonitor.newChild(1));
		} else {
			subMonitor.worked(1);
		}

		// move resource
		long currentStamp= fSource.getModificationStamp();
		IPath destinationPath= fTarget.getFullPath().append(fNewName);
		fSource.move(destinationPath, IResource.KEEP_HISTORY | IResource.SHALLOW, subMonitor.newChild(2));
		resourceAtDestination= ResourcesPlugin.getWorkspace().getRoot().findMember(destinationPath);

		// restore timestamp at destination
		if (fStampToRestore != IResource.NULL_STAMP) {
			resourceAtDestination.revertModificationStamp(fStampToRestore);
		}

		// restore file at source
		if (fRestoreSourceChange != null) {
			performSourceRestore(subMonitor.newChild(1));
		} else {
			subMonitor.worked(1);
		}
		return new MoveRenameResourceChange(resourceAtDestination, fSource.getParent(), fSource.getName(), currentStamp, deleteUndo);
	}

	private Change performDestinationDelete(IResource newResource, IProgressMonitor monitor) throws CoreException {
		monitor.beginTask(RefactoringCoreMessages.MoveResourceChange_progress_delete_destination, 3);
		try {
			DeleteResourceChange deleteChange= new DeleteResourceChange(newResource.getFullPath(), true);
			deleteChange.initializeValidationData(new SubProgressMonitor(monitor, 1));
			RefactoringStatus deleteStatus= deleteChange.isValid(new SubProgressMonitor(monitor, 1));
			if (!deleteStatus.hasFatalError()) {
				return deleteChange.perform(new SubProgressMonitor(monitor, 1));
			}
			return null;
		} finally {
			monitor.done();
		}
	}

	private void performSourceRestore(IProgressMonitor monitor) throws CoreException {
		monitor.beginTask(RefactoringCoreMessages.MoveResourceChange_progress_restore_source, 3);
		try {
			fRestoreSourceChange.initializeValidationData(new SubProgressMonitor(monitor, 1));
			RefactoringStatus restoreStatus= fRestoreSourceChange.isValid(new SubProgressMonitor(monitor, 1));
			if (!restoreStatus.hasFatalError()) {
				fRestoreSourceChange.perform(new SubProgressMonitor(monitor, 1));
			}
		} finally {
			monitor.done();
		}
	}

	@Override
	protected IResource getModifiedResource() {
		return fSource;
	}

	@Override
	public String getName() {
		return Messages.format(RefactoringCoreMessages.MoveResourceChange_name, new String[] { BasicElementLabels.getPathLabel(fSource.getFullPath(), false), BasicElementLabels.getResourceName(fTarget) });
	}
}