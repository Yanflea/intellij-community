/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.cvsExecution;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsResultEx;
import com.intellij.cvsSupport2.config.CvsConfiguration;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.cvsSupport2.ui.CvsTabbedWindow;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.pom.Navigatable;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.ErrorTreeView;
import com.intellij.util.ui.MessageCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * author: lesya
 */
public class CvsOperationExecutor {

  private static final Logger LOG = Logger.getInstance("#com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor");

  private final CvsResultEx myResult = new CvsResultEx();

  private final boolean myShowProgress;
  private final Project myProject;
  private final ModalityContext myExecutor;
  private boolean myShowErrors = true;
  private boolean myIsQuietOperation = false;
  @Nullable private final CvsConfiguration myConfiguration;

  public CvsOperationExecutor(boolean showProgress, Project project, ModalityState modalityState) {
    myProject = project;
    myShowProgress = showProgress;
    myExecutor = new ModalityContextImpl(modalityState);
    myConfiguration = project != null ? CvsConfiguration.getInstance(project) : null;
  }

  public CvsOperationExecutor(boolean showProgress, Project project, ModalityContext modalityContext) {
    myProject = project;
    myShowProgress = showProgress;
    myExecutor = modalityContext;
    myConfiguration = project != null ? CvsConfiguration.getInstance(project) : null;
  }

  public CvsOperationExecutor(Project project) {
    this(true, project, ModalityState.defaultModalityState());
  }

  public CvsOperationExecutor(Project project, ModalityState modalityState) {
    this(true, project, modalityState);
  }

  public void performActionSync(final CvsHandler handler, final CvsOperationExecutorCallback callback) {
    final CvsTabbedWindow tabbedWindow = myIsQuietOperation ? null : openTabbedWindow(handler);

    final Runnable finish = new Runnable() {
      @Override
      public void run() {
        try {
          myResult.addAllErrors(handler.getErrorsExceptAborted());
          handler.finish();
          if (myProject == null || myProject != null && !myProject.isDisposed()) {
            showErrors(handler, tabbedWindow);
          }
        }
        finally {
          try {
            if (myResult.finishedUnsuccessfully(handler)) {
              callback.executionFinished(false);
            }
            else {
              if (handler.getErrors().isEmpty()) callback.executionFinishedSuccessfully();
              callback.executionFinished(true);
            }
          }
          finally {
            if (myProject != null && handler != CvsHandler.NULL) {
              StatusBar.Info.set(getStatusMessage(handler), myProject);
            }
          }
        }
      }
    };

    final Runnable cvsAction = new Runnable() {
      @Override
      public void run() {
        try {
          if (handler == CvsHandler.NULL) return;
          setText(CvsBundle.message("progress.text.preparing.for.login"));

          handler.beforeLogin();

          if (myResult.finishedUnsuccessfully(handler)) return;

          setText(CvsBundle.message("progress.text.preparing.for.action", handler.getTitle()));

          handler.run(myProject, myExecutor);
          if (myResult.finishedUnsuccessfully(handler)) return;

        }
        catch (ProcessCanceledException ex) {
          myResult.setIsCanceled();
        }
        finally {
          callback.executeInProgressAfterAction(myExecutor);
        }
      }
    };

    if (doNotShowProgress()) {
      cvsAction.run();
      if (myIsQuietOperation) {
        finish.run();
      } else {
        myExecutor.runInDispatchThread(finish, myProject);
      }
    }
    else {
      final PerformInBackgroundOption backgroundOption = handler.getBackgroundOption(myProject);
      if (backgroundOption != null) {
        final Task.Backgroundable task = new Task.Backgroundable(myProject, handler.getTitle(), handler.canBeCanceled(), backgroundOption) {
          @Override
          public void run(@NotNull final ProgressIndicator indicator) {
            cvsAction.run();
          }

          @Override
          public void onSuccess() {
            finish.run();
          }
        };
        ProgressManager.getInstance().run(task);
      }
      else {
        if (ProgressManager.getInstance().runProcessWithProgressSynchronously(cvsAction, handler.getTitle(), handler.canBeCanceled(), myProject)) {
          finish.run();
        }
      }
    }
  }

  private static void setText(String text) {
    ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    if (progressIndicator != null) {
      progressIndicator.setText(text);
    }
  }

  private boolean doNotShowProgress() {
    return isInProgress() || isInTestMode() || !myShowProgress || !ApplicationManager.getApplication().isDispatchThread();
  }

  private static boolean isInTestMode() {
    return ApplicationManager.getApplication().isUnitTestMode();
  }

  private static boolean isInProgress() {
    return ProgressManager.getInstance().getProgressIndicator() != null;
  }

  protected void showErrors(final CvsHandler handler, final CvsTabbedWindow tabbedWindow) {
    final List<VcsException> errors = handler.getErrorsExceptAborted();
    if (!myShowErrors || myIsQuietOperation) return;
    if (tabbedWindow == null) {
      if (errors.isEmpty()) return;
      final List<String> messages = new ArrayList<String>();
      for (VcsException error : errors) {
        if (! StringUtil.isEmptyOrSpaces(error.getMessage())) {
          messages.add(error.getMessage());
        }
      }
      final String errorMessage = StringUtil.join(messages, "\n");
      Messages.showErrorDialog(errorMessage, "CVS Error");
      return;
    }
    if (errors.isEmpty()) {
      tabbedWindow.hideErrors();
    }
    else {
      ErrorTreeView errorTreeView = tabbedWindow.getErrorsTreeView();
      for (final VcsException exception : errors) {
        final String groupName = DateFormatUtil.formatDateTime(System.currentTimeMillis()) + ' ' + handler.getTitle();
        if (exception.isWarning()) {
          errorTreeView.addMessage(MessageCategory.WARNING, exception.getMessages(), groupName, DummyNavigatable.INSTANCE,
                                   null, null, exception);
        } else {
          errorTreeView.addMessage(MessageCategory.ERROR, exception.getMessages(), groupName, DummyNavigatable.INSTANCE,
                                   null, null, exception);
        }
      }
      tabbedWindow.ensureVisible(myProject);
    }
  }

  @NotNull private static Editor createView(Project project) {
    EditorFactory editorFactory = EditorFactory.getInstance();
    Document document = editorFactory.createDocument("");
    Editor result = editorFactory.createViewer(document, project);

    EditorSettings editorSettings = result.getSettings();
    editorSettings.setLineMarkerAreaShown(false);
    editorSettings.setLineNumbersShown(false);
    editorSettings.setIndentGuidesShown(false);
    editorSettings.setFoldingOutlineShown(false);
    return result;
  }

  private static String getStatusMessage(final CvsHandler handler) {
    final String actionName = handler.getTitle();
    if (handler.getErrors().isEmpty()) {
      return CvsBundle.message("status.text.action.completed", actionName);
    } else {
      return CvsBundle.message("status.text.action.completed.with.errors", actionName);
    }
  }

  @Nullable
  public CvsTabbedWindow openTabbedWindow(final CvsHandler output) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return null;
    if (myProject != null && myProject.isDefault()) return null;
    if (myProject != null) {
      if (myConfiguration != null && myConfiguration.SHOW_OUTPUT && !myIsQuietOperation) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
          connectToOutput(output);
        } else {
          ApplicationManager.getApplication().invokeAndWait(new Runnable() {
            public void run() {
              connectToOutput(output);
            }
          }, ModalityState.defaultModalityState());
        }
      }
      if (!myProject.isDisposed()) {
        return CvsTabbedWindow.getInstance(myProject);
      }
    }
    return null;
  }

  private void connectToOutput(CvsHandler output) {
    CvsTabbedWindow tabbedWindow = CvsTabbedWindow.getInstance(myProject);
    Editor editor = tabbedWindow.getOutput();
    if (editor == null) {
      output.connectToOutputView(tabbedWindow.addOutput(createView(myProject)), myProject);
    } else {
      output.connectToOutputView(editor, myProject);
    }
  }

  public VcsException getFirstError() {
    return myResult.composeError();
  }

  public boolean hasNoErrors() {
    return !myResult.hasErrors();
  }

  public CvsResult getResult() {
    return myResult;
  }

  private static class DummyNavigatable implements Navigatable {
    public static final Navigatable INSTANCE = new DummyNavigatable();

    private DummyNavigatable() {}

    @Override
    public void navigate(boolean requestFocus) {}

    @Override
    public boolean canNavigate() {
      return false;
    }

    @Override
    public boolean canNavigateToSource() {
      return false;
    }
  }

  public void setShowErrors(boolean showErrors) {
    myShowErrors = showErrors;
  }

  public void setIsQuietOperation(final boolean isQuietOperation) {
    myIsQuietOperation = isQuietOperation;
  }
}
