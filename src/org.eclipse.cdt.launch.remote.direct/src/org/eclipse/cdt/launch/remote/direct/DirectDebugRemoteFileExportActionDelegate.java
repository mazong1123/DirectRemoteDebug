package org.eclipse.cdt.launch.remote.direct;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IAction;
import org.eclipse.osgi.util.NLS;
import org.eclipse.rse.internal.importexport.IRemoteImportExportConstants;
import org.eclipse.rse.internal.importexport.RemoteImportExportPlugin;
import org.eclipse.rse.internal.importexport.RemoteImportExportResources;
import org.eclipse.rse.internal.importexport.files.IRemoteFileExportDescriptionReader;
import org.eclipse.rse.internal.importexport.files.RemoteFileExportData;
import org.eclipse.rse.internal.importexport.files.RemoteFileImportExportActionDelegate;
import org.eclipse.rse.internal.importexport.files.RemoteFileOverwriteQuery;
import org.eclipse.rse.internal.importexport.files.Utilities;
import org.eclipse.rse.internal.synchronize.SynchronizeData;
import org.eclipse.rse.internal.synchronize.provisional.ISynchronizeOperation;
import org.eclipse.rse.internal.synchronize.provisional.SynchronizeOperation;
import org.eclipse.rse.internal.synchronize.provisional.Synchronizer;
import org.eclipse.rse.services.clientserver.messages.SimpleSystemMessage;
import org.eclipse.rse.services.clientserver.messages.SystemMessage;
import org.eclipse.rse.ui.SystemBasePlugin;

/**
 * This class is a remote file export action.
 */
public class DirectDebugRemoteFileExportActionDelegate extends RemoteFileImportExportActionDelegate {
	private IProgressMonitor monitor = null;

	public IProgressMonitor getMonitor() {
		return this.monitor;
	}

	public void setMonitor(IProgressMonitor monitor) {
		this.monitor = monitor;
	}

	/**
	 * @see org.eclipse.ui.IActionDelegate#run(org.eclipse.jface.action.IAction)
	 */
	public void run(IAction action) {
		IFile[] descriptions = getDescriptionFiles(getSelection());
		MultiStatus mergedStatus;
		int length = descriptions.length;
		if (length < 1) {
			return;
		}
		// create read multi status
		String message;
		if (length > 1) {
			message = RemoteImportExportResources.IMPORT_EXPORT_ERROR_CREATE_FILES_FAILED;
		} else {
			message = RemoteImportExportResources.IMPORT_EXPORT_ERROR_CREATE_FILE_FAILED;
		}
		MultiStatus readStatus = new MultiStatus(RemoteImportExportPlugin.getDefault().getSymbolicName(), 0, message,
				null);
		RemoteFileExportData[] exportDatas = readExportDatas(descriptions, readStatus);
		if (exportDatas.length > 0) {
			IStatus status = export(exportDatas, this.monitor);
			if (status == null) {
				return;
			}
			if (readStatus.getSeverity() == IStatus.ERROR) {
				message = readStatus.getMessage();
			} else {
				message = status.getMessage();
			}
			// create new status because we want another message - no API to set
			// message
			mergedStatus = new MultiStatus(RemoteImportExportPlugin.getDefault().getSymbolicName(), status.getCode(),
					readStatus.getChildren(), message, null);
			mergedStatus.merge(status);
		} else {
			mergedStatus = readStatus;
		}
		if (!mergedStatus.isOK()) {
			// RemoteImportExportProblemDialog.open(getShell(),
			// RemoteImportExportResources.IMPORT_EXPORT_EXPORT_ACTION_DELEGATE_TITLE,
			// null, mergedStatus);
			throw new RuntimeException("merged status is not ok.");
		}
	}

	private RemoteFileExportData[] readExportDatas(IFile[] descriptions, MultiStatus readStatus) {
		List exportDataList = new ArrayList(descriptions.length);
		for (int i = 0; i < descriptions.length; i++) {
			RemoteFileExportData exportData = readExportData(descriptions[i], readStatus);
			if (exportData != null) {
				exportDataList.add(exportData);
			}
		}
		return (RemoteFileExportData[]) exportDataList.toArray(new RemoteFileExportData[exportDataList.size()]);
	}

	/**
	 * Reads the file export data from a file.
	 */
	protected RemoteFileExportData readExportData(IFile description, MultiStatus readStatus) {
		Assert.isLegal(description.isAccessible());
		Assert.isNotNull(description.getFileExtension());
		Assert.isLegal(description.getFileExtension().equals(Utilities.EXPORT_DESCRIPTION_EXTENSION));
		RemoteFileExportData exportData = new RemoteFileExportData();
		IRemoteFileExportDescriptionReader reader = null;
		try {
			reader = exportData.createExportDescriptionReader(description.getContents());
			// read export data
			reader.read(exportData);
			// do not save settings again
			exportData.setSaveSettings(false);
		} catch (CoreException ex) {
			String message = NLS.bind(RemoteImportExportResources.IMPORT_EXPORT_ERROR_DESCRIPTION_READ,
					description.getFullPath(), ex.getStatus().getMessage());
			addToStatus(readStatus, message, ex);
			return null;
		} finally {
			if (reader != null) {
				readStatus.addAll(reader.getStatus());
			}
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (CoreException ex) {
				String message = NLS.bind(RemoteImportExportResources.IMPORT_EXPORT_ERROR_DESCRIPTION_CLOSE,
						description.getFullPath());
				addToStatus(readStatus, message, ex);
			}
		}
		return exportData;
	}

	private IStatus export(RemoteFileExportData[] exportDatas, IProgressMonitor monitor) {
		// Add re-running synchronize operation
		for (int i = 0; i < exportDatas.length; i++) {
			RemoteFileExportData exportData = exportDatas[i];
			if (exportData.isReviewSynchronize()) {

				SynchronizeData data = new SynchronizeData(exportData);
				data.setSynchronizeType(ISynchronizeOperation.SYNC_MODE_UI_REVIEW);
				new Synchronizer(data).run(new SynchronizeOperation());
			} else {
				IStatus status = null;

				DirectDebugRemoteFileExportOperation op = new DirectDebugRemoteFileExportOperation(exportDatas[i],
						new RemoteFileOverwriteQuery());
				
				Boolean isSuccess = true;
				try{
					op.run(this.monitor);
					status = op.getStatus();
					if (!status.isOK()) {
						isSuccess = false;
					}
				}
				catch  (InterruptedException ex) {
					isSuccess = false;
				}

				if (!isSuccess) {
					String msgTxt = NLS.bind(RemoteImportExportResources.FILEMSG_EXPORT_FAILED, status);

					SystemMessage msg = new SimpleSystemMessage(RemoteImportExportPlugin.PLUGIN_ID,
							IRemoteImportExportConstants.FILEMSG_EXPORT_FAILED, IStatus.ERROR, msgTxt);

					/*
					 * SystemMessageDialog dlg = new
					 * SystemMessageDialog(getShell(), msg);
					 * dlg.openWithDetails();
					 */

					throw new RuntimeException(msg.toString());
				}
			}
		}
		return null;
	}

	protected void addToStatus(MultiStatus multiStatus, String defaultMessage, CoreException ex) {
		IStatus status = ex.getStatus();
		String message = ex.getLocalizedMessage();
		if (message == null || message.length() < 1) {
			status = new Status(status.getSeverity(), status.getPlugin(), status.getCode(), defaultMessage, ex);
		}
		multiStatus.add(status);
	}
}
