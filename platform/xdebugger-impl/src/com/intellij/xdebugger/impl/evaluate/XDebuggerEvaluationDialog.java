/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.xdebugger.impl.evaluate;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerBundle;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import com.intellij.xdebugger.impl.ui.XDebuggerEditorBase;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreePanel;
import com.intellij.xdebugger.impl.ui.tree.nodes.EvaluatingExpressionRootNode;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * @author nik
 */
public class XDebuggerEvaluationDialog extends DialogWrapper {
  private final JPanel myMainPanel;
  private final JPanel myResultPanel;
  private final XDebuggerTreePanel myTreePanel;
  private EvaluationInputComponent myInputComponent;
  private final XDebuggerEvaluator myEvaluator;
  private final XDebugSession mySession;
  private final XDebuggerEditorsProvider myEditorsProvider;
  private EvaluationMode myMode;
  private final XSourcePosition mySourcePosition;
  private final SwitchModeAction mySwitchModeAction;

  public XDebuggerEvaluationDialog(@NotNull XDebugSession session,
                                   final @NotNull XDebuggerEditorsProvider editorsProvider,
                                   @NotNull XDebuggerEvaluator evaluator,
                                   @NotNull String text,
                                   final XSourcePosition sourcePosition) {
    super(session.getProject(), true);
    mySession = session;
    myEditorsProvider = editorsProvider;
    mySourcePosition = sourcePosition;
    setModal(false);
    setOKButtonText(XDebuggerBundle.message("xdebugger.button.evaluate"));
    setCancelButtonText(XDebuggerBundle.message("xdebugger.evaluate.dialog.close"));

    myTreePanel = new XDebuggerTreePanel(session, editorsProvider, myDisposable, sourcePosition, XDebuggerActions.EVALUATE_DIALOG_TREE_POPUP_GROUP);
    myResultPanel = new JPanel(new BorderLayout());
    myResultPanel.add(new JLabel(XDebuggerBundle.message("xdebugger.evaluate.label.result")), BorderLayout.NORTH);
    myResultPanel.add(myTreePanel.getMainPanel(), BorderLayout.CENTER);
    myEvaluator = evaluator;
    myMainPanel = new JPanel(new BorderLayout());

    mySwitchModeAction = new SwitchModeAction();

    new AnAction(){
      public void actionPerformed(AnActionEvent e) {
        doOKAction();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)), getRootPane(), myDisposable);
    new AnAction() {
      public void actionPerformed(AnActionEvent e) {
        IdeFocusManager.getInstance(mySession.getProject()).requestFocus(myTreePanel.getTree(), true);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.ALT_DOWN_MASK)), getRootPane(), myDisposable);

    EvaluationMode mode = EvaluationMode.EXPRESSION;
    if (text.indexOf('\n') != -1) {
      if (myEvaluator.isCodeFragmentEvaluationSupported()) {
        mode = EvaluationMode.CODE_FRAGMENT;
      }
      else {
        text = StringUtil.replace(text, "\n", " ");
      }
    }
    switchToMode(mode, text);
    init();
  }

  protected void doOKAction() {
    evaluate();
  }

  @Override
  protected Action[] createActions() {
    if (myEvaluator.isCodeFragmentEvaluationSupported()) {
      return new Action[]{getOKAction(), mySwitchModeAction, getCancelAction()};
    }
    return super.createActions();
  }

  @Override
  protected JButton createJButtonForAction(Action action) {
    final JButton button = super.createJButtonForAction(action);
    if (action == mySwitchModeAction) {
      int width1 = new JButton(getSwitchButtonText(EvaluationMode.EXPRESSION)).getPreferredSize().width;
      int width2 = new JButton(getSwitchButtonText(EvaluationMode.CODE_FRAGMENT)).getPreferredSize().width;
      final Dimension size = new Dimension(Math.max(width1, width2), button.getPreferredSize().height);
      button.setMinimumSize(size);
      button.setPreferredSize(size);
    }
    return button;
  }

  public String getExpression() {
    return myInputComponent.getInputEditor().getText();
  }

  private static String getSwitchButtonText(EvaluationMode mode) {
    return mode != EvaluationMode.EXPRESSION
           ? XDebuggerBundle.message("button.text.expression.mode")
           : XDebuggerBundle.message("button.text.code.fragment.mode");
  }

  private void switchToMode(EvaluationMode mode, String text) {
    if (myMode == mode) return;
    myMode = mode;

    myInputComponent = createInputComponent(mode, text);
    myMainPanel.removeAll();
    myInputComponent.addComponent(myMainPanel, myResultPanel);

    setTitle(myInputComponent.getTitle());
    mySwitchModeAction.putValue(Action.NAME, getSwitchButtonText(mode));
    final JComponent preferredFocusedComponent = myInputComponent.getInputEditor().getPreferredFocusedComponent();
    if (preferredFocusedComponent != null) {
      IdeFocusManager.getInstance(mySession.getProject()).requestFocus(preferredFocusedComponent, true);
    }
  }

  private EvaluationInputComponent createInputComponent(EvaluationMode mode, String text) {
    final Project project = mySession.getProject();
    if (mode == EvaluationMode.EXPRESSION) {
      return new ExpressionInputComponent(project, myEditorsProvider, mySourcePosition, text);
    }
    else {
      return new CodeFragmentInputComponent(project, myEditorsProvider, mySourcePosition, text, myDisposable);
    }
  }

  private void evaluate() {
    final XDebuggerTree tree = myTreePanel.getTree();
    final EvaluatingExpressionRootNode root = new EvaluatingExpressionRootNode(this, tree);
    tree.setRoot(root, false);
    myResultPanel.invalidate();
    myInputComponent.getInputEditor().selectAll();
  }

  protected String getDimensionServiceKey() {
    return "#xdebugger.evaluate";
  }

  protected JComponent createCenterPanel() {
    return myMainPanel;
  }

  public void startEvaluation(XDebuggerEvaluator.XEvaluationCallback evaluationCallback) {
    final XDebuggerEditorBase inputEditor = myInputComponent.getInputEditor();
    inputEditor.saveTextInHistory();
    String expression = inputEditor.getText();
    myEvaluator.evaluate(expression, evaluationCallback, null, inputEditor.getMode());
  }

  public JComponent getPreferredFocusedComponent() {
    return myInputComponent.getInputEditor().getPreferredFocusedComponent();
  }

  private class SwitchModeAction extends AbstractAction {
    public void actionPerformed(ActionEvent e) {
      String text = myInputComponent.getInputEditor().getText();
      if (myMode == EvaluationMode.EXPRESSION) {
        switchToMode(EvaluationMode.CODE_FRAGMENT, text);
      }
      else {
        if (text.indexOf('\n') != -1) text = "";
        switchToMode(EvaluationMode.EXPRESSION, text);
      }
    }
  }
}
