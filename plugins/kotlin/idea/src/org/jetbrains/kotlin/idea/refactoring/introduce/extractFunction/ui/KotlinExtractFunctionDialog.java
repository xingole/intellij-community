// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.introduce.extractFunction.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.ui.NameSuggestionsField;
import com.intellij.ui.TitledSeparator;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.MultiMap;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.KotlinFileType;
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle;
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.*;
import org.jetbrains.kotlin.idea.refactoring.introduce.ui.KotlinSignatureComponent;
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers;
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.psiUtil.KtPsiUtilKt;
import org.jetbrains.kotlin.types.KotlinType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jetbrains.kotlin.idea.refactoring.KotlinCommonRefactoringUtilKt.checkConflictsInteractively;
import static org.jetbrains.kotlin.idea.util.NonblockingKt.nonBlocking;

public class KotlinExtractFunctionDialog extends DialogWrapper {
    private JPanel contentPane;
    private TitledSeparator inputParametersPanel;
    private JComboBox visibilityBox;
    private KotlinSignatureComponent signaturePreviewField;
    private JPanel functionNamePanel;
    private NameSuggestionsField functionNameField;
    private JLabel functionNameLabel;
    private JComboBox<KotlinType> returnTypeBox;
    private JPanel returnTypePanel;
    private ExtractFunctionParameterTablePanel parameterTablePanel;

    private final Project project;

    private final ExtractableCodeDescriptorWithConflicts originalDescriptor;
    private ExtractableCodeDescriptor currentDescriptor;

    private final Function1<KotlinExtractFunctionDialog, Unit> onAccept;

    public KotlinExtractFunctionDialog(
            @NotNull Project project,
            @NotNull ExtractableCodeDescriptorWithConflicts originalDescriptor,
            @NotNull Function1<KotlinExtractFunctionDialog, Unit> onAccept) {
        super(project, true);

        this.project = project;
        this.originalDescriptor = originalDescriptor;
        this.currentDescriptor = originalDescriptor.getDescriptor();
        this.onAccept = onAccept;

        setModal(true);
        setTitle(KotlinBundle.message("extract.function"));
        init();
        update();
    }

    private void createUIComponents() {
        this.signaturePreviewField = new KotlinSignatureComponent("", project);
    }

    private boolean isVisibilitySectionAvailable() {
        return ExtractUtilKt.isVisibilityApplicable(originalDescriptor.getDescriptor().getExtractionData());
    }

    private String getFunctionName() {
        return KtPsiUtilKt.quoteIfNeeded(functionNameField.getEnteredName());
    }

    private @Nullable KtModifierKeywordToken getVisibility() {
        if (!isVisibilitySectionAvailable()) return null;

        KtModifierKeywordToken value = (KtModifierKeywordToken) visibilityBox.getSelectedItem();
        return KtTokens.DEFAULT_VISIBILITY_KEYWORD.equals(value) ? null : value;
    }

    private boolean checkNames() {
        if (!KtPsiUtilKt.isIdentifier(getFunctionName())) return false;
        for (ExtractFunctionParameterTablePanel.ParameterInfo parameterInfo : parameterTablePanel.getSelectedParameterInfos()) {
            if (!KtPsiUtilKt.isIdentifier(parameterInfo.getName())) return false;
        }
        return true;
    }

    private void update() {
        this.currentDescriptor = createDescriptor();

        setOKActionEnabled(checkNames());
        signaturePreviewField.setText(
                ExtractorUtilKt.getSignaturePreview(getCurrentConfiguration())
        );
    }

    @Override
    protected void init() {
        super.init();

        ExtractableCodeDescriptor extractableCodeDescriptor = originalDescriptor.getDescriptor();

        functionNameField = new NameSuggestionsField(
                ArrayUtil.toStringArray(extractableCodeDescriptor.getSuggestedNames()),
                project,
                KotlinFileType.INSTANCE
        );
        functionNameField.addDataChangedListener(() -> update());
        functionNamePanel.add(functionNameField, BorderLayout.CENTER);
        functionNameLabel.setLabelFor(functionNameField);

        List<KotlinType> possibleReturnTypes = ExtractableCodeDescriptorKt.getPossibleReturnTypes(extractableCodeDescriptor.getControlFlow());
        if (possibleReturnTypes.size() > 1) {
            DefaultComboBoxModel<KotlinType> returnTypeBoxModel = new DefaultComboBoxModel<>(possibleReturnTypes.toArray(new KotlinType[0]));
            returnTypeBox.setModel(returnTypeBoxModel);
            returnTypeBox.setRenderer(
                    new DefaultListCellRenderer() {
                        @Override
                        public @NotNull Component getListCellRendererComponent(
                                JList list,
                                Object value,
                                int index,
                                boolean isSelected,
                                boolean cellHasFocus
                        ) {
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                            @NlsSafe
                            String text = IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.renderType((KotlinType) value);
                            setText(text);
                            return this;
                        }
                    }
            );
            returnTypeBox.addItemListener(
                    new ItemListener() {
                        @Override
                        public void itemStateChanged(@NotNull ItemEvent e) {
                            update();
                        }
                    }
            );
        }
        else {
            returnTypePanel.getParent().remove(returnTypePanel);
        }

        visibilityBox.setModel(new DefaultComboBoxModel(KtTokens.VISIBILITY_MODIFIERS.getTypes()));

        boolean enableVisibility = isVisibilitySectionAvailable();
        visibilityBox.setEnabled(enableVisibility);
        if (enableVisibility) {
            KtModifierKeywordToken defaultVisibility = extractableCodeDescriptor.getVisibility();
            visibilityBox.setSelectedItem(defaultVisibility);
        }
        visibilityBox.addItemListener(
                new ItemListener() {
                    @Override
                    public void itemStateChanged(@NotNull ItemEvent e) {
                        update();
                    }
                }
        );

        parameterTablePanel = new ExtractFunctionParameterTablePanel() {
            @Override
            protected void updateSignature() {
                KotlinExtractFunctionDialog.this.update();
            }

            @Override
            protected void onEnterAction() {
                doOKAction();
            }

            @Override
            protected void onCancelAction() {
                doCancelAction();
            }
        };
        parameterTablePanel.init(extractableCodeDescriptor.getReceiverParameter(), extractableCodeDescriptor.getParameters());

        inputParametersPanel.setText(KotlinBundle.message("text.parameters"));
        inputParametersPanel.setLabelFor(parameterTablePanel.getTable());
        inputParametersPanel.add(parameterTablePanel);
    }

    @Override
    protected void doOKAction() {
        nonBlocking(
                project,
                () -> {
                    try {
                        return ExtractableAnalysisUtilKt.validate(currentDescriptor);
                    } catch (RuntimeException e) {
                        return new ExtractableCodeDescriptorWithException(e);
                    }
                },
                result -> {
                    if (result instanceof ExtractableCodeDescriptorWithException) {
                        throw ((ExtractableCodeDescriptorWithException) result).getException();
                    }
                    MultiMap<PsiElement, String> conflicts = ((ExtractableCodeDescriptorWithConflicts) result).getConflicts();
                    conflicts.values().removeAll(originalDescriptor.getConflicts().values());

                    checkConflictsInteractively(
                            project,
                            conflicts,
                            new Function0<>() {
                                @Override
                                public Unit invoke() {
                                    close(OK_EXIT_CODE);
                                    return Unit.INSTANCE;
                                }
                            },
                            new Function0<>() {
                                @Override
                                public Unit invoke() {
                                    KotlinExtractFunctionDialog.super.doOKAction();
                                    return onAccept.invoke(KotlinExtractFunctionDialog.this);
                                }
                            }
                    );
                    return Unit.INSTANCE;
                });
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return functionNameField.getFocusableComponent();
    }

    @Override
    protected JComponent createCenterPanel() {
        return contentPane;
    }

    @Override
    protected @NotNull JComponent createContentPane() {
        return contentPane;
    }

    private @NotNull ExtractableCodeDescriptor createDescriptor() {
        return createNewDescriptor(originalDescriptor.getDescriptor(),
                                   getFunctionName(),
                                   getVisibility(),
                                   parameterTablePanel.getSelectedReceiverInfo(),
                                   parameterTablePanel.getSelectedParameterInfos(),
                                   (KotlinType) returnTypeBox.getSelectedItem());
    }

    public @NotNull ExtractionGeneratorConfiguration getCurrentConfiguration() {
        return new ExtractionGeneratorConfiguration(currentDescriptor, ExtractionGeneratorOptions.DEFAULT);
    }

    public static ExtractableCodeDescriptor createNewDescriptor(
            @NotNull ExtractableCodeDescriptor originalDescriptor,
            @NotNull String newName,
            @Nullable KtModifierKeywordToken newVisibility,
            @Nullable ExtractFunctionParameterTablePanel.ParameterInfo newReceiverInfo,
            @NotNull List<ExtractFunctionParameterTablePanel.ParameterInfo> newParameterInfos,
            @Nullable KotlinType returnType
    ) {
        Map<Parameter, Parameter> oldToNewParameters = new LinkedHashMap<>();
        for (ExtractFunctionParameterTablePanel.ParameterInfo parameterInfo : newParameterInfos) {
            oldToNewParameters.put(parameterInfo.getOriginalParameter(), parameterInfo.toParameter());
        }
        Parameter originalReceiver = originalDescriptor.getReceiverParameter();
        Parameter newReceiver = newReceiverInfo != null ? newReceiverInfo.toParameter() : null;
        if (originalReceiver != null && newReceiver != null) {
            oldToNewParameters.put(originalReceiver, newReceiver);
        }

        return ExtractableCodeDescriptorKt.copy(originalDescriptor, newName, newVisibility, oldToNewParameters, newReceiver, returnType);
    }
}
