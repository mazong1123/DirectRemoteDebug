package org.eclipse.cdt.launch.remote.direct;

import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.debug.core.CDebugUtils;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.debug.core.sourcelookup.MappingSourceContainer;
import org.eclipse.cdt.debug.internal.core.sourcelookup.MapEntrySourceContainer;
import org.eclipse.cdt.dsf.concurrent.DsfRunnable;
import org.eclipse.cdt.dsf.concurrent.ImmediateRequestMonitor;
import org.eclipse.cdt.dsf.debug.service.IDsfDebugServicesFactory;
import org.eclipse.cdt.dsf.debug.sourcelookup.DsfSourceLookupDirector;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunchDelegate;
import org.eclipse.cdt.dsf.gdb.launching.LaunchUtils;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.sourcelookup.ISourceContainer;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.rse.core.RSECorePlugin;
import org.eclipse.rse.core.subsystems.ISubSystem;
import org.eclipse.rse.internal.importexport.RemoteImportExportUtil;
import org.eclipse.rse.internal.synchronize.RSESyncUtils;
import org.eclipse.rse.services.shells.HostShellProcessAdapter;
import org.eclipse.rse.services.shells.IHostOutput;
import org.eclipse.rse.services.shells.IHostShell;
import org.eclipse.rse.services.shells.IHostShellChangeEvent;
import org.eclipse.rse.services.shells.IHostShellOutputListener;
import org.eclipse.rse.services.shells.IHostShellOutputReader;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public class DirectRemoteDebugLaunchDelegate extends GdbLaunchDelegate {
	private String version = ""; //$NON-NLS-1$
	private IHostShell remoteShell = null;
	private Process remoteProcess = null;
	private static String DIRECT_REMOTE_DEBUG_MAPPING = "DirectRemoteDebugMapping";

	private class DummyAction extends Action {
		/**
		 * Constructor.
		 */
		public DummyAction() {
			super();
		}
	}

	public DirectRemoteDebugLaunchDelegate() {
		super();
	}

	protected IHostShell getShell() {
		return remoteShell;
	}

	protected Process getRemoteProcess() {
		return remoteProcess;
	}

	@Override
	protected IDsfDebugServicesFactory newServiceFactory(ILaunchConfiguration config, String version) {
		return new DirectRemoteServicesFactory(version, this);
	}

	private void uploadSourceCodeToRemoteWorkSpace(ILaunchConfiguration config, IProgressMonitor monitor)
			throws CoreException {
		IProject projectHandle = CDebugUtils.getCProject(config).getProject();

		IResource exportConfigResource = null;
		IResource[] rootFiles = projectHandle.members();
		for (int i = 0; i < rootFiles.length; i++) {
			IResource r = rootFiles[i];
			String name = r.getName();
			if (name.endsWith(".rexpfd")) {
				exportConfigResource = r;

				break;
			}
		}

		if (exportConfigResource != null) {
			//throw new RuntimeException("Not found export config file(*.rexpfd).");
			DirectDebugRemoteFileExportActionDelegate action = new DirectDebugRemoteFileExportActionDelegate();
			action.setMonitor(monitor);
			DummyAction dummy = new DummyAction();
			action.selectionChanged(dummy, new StructuredSelection(exportConfigResource));
			action.run(dummy);
		}
	}

	@Override
	public void launch(ILaunchConfiguration config, String mode, ILaunch launch, IProgressMonitor monitor)
			throws CoreException {

		// Need to initialize RSE
		if (!RSECorePlugin.isInitComplete(RSECorePlugin.INIT_MODEL)) {
			monitor.subTask(Messages.DirectRemoteDebugLaunchDelegate_1);
			try {
				RSECorePlugin.waitForInitCompletion(RSECorePlugin.INIT_MODEL);
			} catch (InterruptedException e) {
				throw new CoreException(
						new Status(IStatus.ERROR, getPluginID(), IStatus.OK, e.getLocalizedMessage(), e));
			}
		}

		// First, let's upload source code to the remote workspace.
		this.uploadSourceCodeToRemoteWorkSpace(config, monitor);

		remoteProcess = null;
		IPath gdbCommmand = LaunchUtils.getGDBPath(config);
		String prelaunchCmd = config.getAttribute(IDirectRemoteConnectionConfigurationConstants.ATTR_PRERUN_COMMANDS,
				""); //$NON-NLS-1$
		monitor.setTaskName(Messages.DirectRemoteDebugLaunchDelegate_3);
		final GdbLaunch l = (GdbLaunch) launch;
		try {
			remoteShell = RSEHelper.execCmdInRemoteShell(config, prelaunchCmd, gdbCommmand.toOSString(), "-version", //$NON-NLS-1$
					new SubProgressMonitor(monitor, 5));
		} catch (Exception el) {
			RSEHelper.abort(el.getMessage(), el, ICDTLaunchConfigurationConstants.ERR_INTERNAL_ERROR);
		}

		// We cannot use a global variable because multiple launches
		// could access them at the same time. We need a different
		// variable for each launch, but we also need it be final.
		// Use a final array to do that.
		final boolean gdbReady[] = new boolean[1];
		gdbReady[0] = false;
		final Object lock = new Object();
		if (remoteShell != null) {
			remoteShell.addOutputListener(new IHostShellOutputListener() {
				boolean gdbInitialized = false;

				@Override
				public void shellOutputChanged(IHostShellChangeEvent event) {

					StringBuilder buf = new StringBuilder();
					for (IHostOutput line : event.getLines()) {
						String lineString = line.getString();
						if (lineString.length() == 0) {
							continue;
						}

						if (lineString.contains("GNU gdb (GDB") && !gdbInitialized) { //$NON-NLS-1$
							version = LaunchUtils.getGDBVersionFromText(lineString);
							synchronized (lock) {
								gdbReady[0] = true;
								lock.notifyAll();
							}

							gdbInitialized = true;
						}

						buf.append(lineString);
						buf.append(System.getProperty("line.separator"));

						/*
						 * if (lineString.contains("This GDB was configured as"
						 * )) { //$NON-NLS-1$ synchronized (lock) { gdbReady[0]
						 * = true; lock.notifyAll(); } working = false; break; }
						 */
					}

					String newContent = null;
					if (buf.length() != 0) {
						newContent = buf.toString();
					} else {
						newContent = "";
					}

					// TODO: output to eclipse console.
				}
			});
			try {
				remoteProcess = new HostShellProcessAdapter(remoteShell);
			} catch (Exception e) {
				RSEHelper.abort(Messages.DirectRemoteDebugLaunchDelegate_5, e,
						ICDTLaunchConfigurationConstants.ERR_INTERNAL_ERROR);
			}
			synchronized (lock) {
				while (gdbReady[0] == false) {
					if (monitor.isCanceled() || !remoteShell.isActive()) {
						if (remoteProcess != null) {
							remoteProcess.destroy();
						}

						try {
							l.getSession().getExecutor().execute(new DsfRunnable() {

								@Override
								public void run() {
									l.shutdownSession(new ImmediateRequestMonitor());

								}
							});
						} catch (RejectedExecutionException e) {

						}
						RSEHelper.abort(Messages.DirectRemoteDebugLaunchDelegate_6, null,
								ICDTLaunchConfigurationConstants.ERR_DEBUGGER_NOT_INSTALLED);
					}
					try {
						lock.wait(500);
					} catch (InterruptedException e) {

					}
				}
			}
		}
		try {
			super.launch(config, mode, launch, monitor);
		} catch (CoreException ex) {
			// launch failed, need to kill gdb
			if (remoteProcess != null) {
				remoteProcess.destroy();
			}
			// report failure further
			throw ex;
		} finally {
			monitor.done();
		}
	}

	@Override
	protected String getPluginID() {
		return Activator.PLUGIN_ID;
	}

	@Override
	protected String getGDBVersion(ILaunchConfiguration config) throws CoreException {
		return version;
	}

	@Override
	protected ISourceLocator getSourceLocator(ILaunchConfiguration configuration, DsfSession session)
			throws CoreException {

		DsfSourceLookupDirector sl = (DsfSourceLookupDirector) super.getSourceLocator(configuration, session);
		ISourceContainer containers[] = sl.getSourceContainers();
		boolean found = false;
		;

		for (ISourceContainer c : containers) {
			if (c.getName() == DIRECT_REMOTE_DEBUG_MAPPING) {
				found = true;
				break;
			}
		}

		if (!found) {
			ISourceContainer newConstrainters[] = new ISourceContainer[containers.length + 1];
			System.arraycopy(containers, 0, newConstrainters, 0, containers.length);
			MappingSourceContainer mapContainer = new MappingSourceContainer(DIRECT_REMOTE_DEBUG_MAPPING);
			ICProject cp = LaunchUtils.getCProject(configuration);
			String remoteWorkSpaceLocation = configuration
					.getAttribute(IDirectRemoteConnectionConfigurationConstants.ATTR_REMOTE_WORKSPACE, ""); //$NON-NLS-1$

			if (cp != null && remoteWorkSpaceLocation.length() > 0) {
				IProject p = cp.getProject();

				MapEntrySourceContainer entry = new MapEntrySourceContainer(Path.fromOSString(remoteWorkSpaceLocation),
						p.getLocation());
				mapContainer.addMapEntry(entry);
				newConstrainters[containers.length] = mapContainer;
				sl.setSourceContainers(newConstrainters);
			}

		}
		return sl;
	}

	@Override
	protected IPath checkBinaryDetails(ILaunchConfiguration config) throws CoreException {
		return null;
	}

	public static IProject getCurrentSelectedProject() {
		// IProject project = null;
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject();
		IWorkbench workBench = PlatformUI.getWorkbench();
		IWorkbenchWindow workBenchWindow = workBench.getActiveWorkbenchWindow();

		ISelectionService selectionService = workBenchWindow.getSelectionService();

		ISelection selection = selectionService.getSelection();

		if (selection instanceof IStructuredSelection) {
			Object element = ((IStructuredSelection) selection).getFirstElement();

			if (element instanceof IResource) {
				project = ((IResource) element).getProject();
			}
		}

		return project;
	}

}
