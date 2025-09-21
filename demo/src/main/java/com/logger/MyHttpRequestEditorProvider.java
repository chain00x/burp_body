/*
 * Copyright (c) 2023. PortSwigger Ltd. All rights reserved.
 *
 * This code may be used to extend the functionality of Burp Suite Community Edition
 * and Burp Suite Professional, provided that this usage does not violate the
 * license terms for those products.
 */

package com.logger;

import burp.api.montoya.MontoyaApi;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.TextAction;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.AbstractDocument;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Objects;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import static burp.api.montoya.core.ByteArray.byteArray;

/**
 * Request editor provider implementation with line wrap support
 */
public class MyHttpRequestEditorProvider implements HttpRequestEditorProvider {
    private final MontoyaApi api;

    /**
     * Constructor
     * @param api Montoya API instance
     */
    public MyHttpRequestEditorProvider(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Provide request editor
     * @param creationContext Editor creation context
     * @return Extension provided request editor
     */
    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        return new MyExtensionProvidedHttpRequestEditor(api, creationContext);
    }

    /**
     * Inner class implementing request editor with line wrap support
     */
    private class MyExtensionProvidedHttpRequestEditor implements ExtensionProvidedHttpRequestEditor {
        private final RawEditor requestEditor; // Burp原生编辑器
        private final RSyntaxTextArea customTextArea; // 我们的自定义文本区域
        private final JPanel mainPanel;
        private HttpRequestResponse requestResponse;
        private final EditorMode editorMode;
        private final MontoyaApi api;
        private boolean isUpdating = false; // 防止无限循环的标志

        // 用于检测不同内容类型的正则表达式
        private final Pattern XML_PATTERN = Pattern.compile("<\\?xml.*?\\?>", Pattern.DOTALL);
        private final Pattern HTML_PATTERN = Pattern.compile("<html", Pattern.CASE_INSENSITIVE);
        private final Pattern JSON_PATTERN = Pattern.compile("^\\s*[{\\[]", Pattern.DOTALL);
        private final Pattern JS_PATTERN = Pattern.compile("function\\s*\\w*\\s*\\(|const\\s+\\w+|let\\s+\\w+|var\\s+\\w+", Pattern.DOTALL);
        private final Pattern CSS_PATTERN = Pattern.compile("[#.]?\\w+\\s*\\{|\\@import|\\@media", Pattern.DOTALL);

        /**
         * Constructor
         * @param api Montoya API instance
         * @param creationContext Editor creation context
         */
        MyExtensionProvidedHttpRequestEditor(MontoyaApi api, EditorCreationContext creationContext) {
            this.api = api;
            this.editorMode = creationContext.editorMode();
            boolean isEditable = editorMode != EditorMode.READ_ONLY;
            
            // 使用Burp的API创建RawEditor，确保基本功能
            if (creationContext.editorMode() == EditorMode.READ_ONLY) {
                requestEditor = api.userInterface().createRawEditor();
                requestEditor.setEditable(false);
            } else {
                requestEditor = api.userInterface().createRawEditor();
                requestEditor.setEditable(true);
            }
            
            // 按照JWT编辑器的方式创建RSyntaxTextArea
            // 先修复键事件冲突，然后创建编辑器实例
            customTextArea = fixKeyEventCapture(() -> new RSyntaxTextArea());
            
            // 配置文本区域属性
            configureRSyntaxTextArea(customTextArea, isEditable);
            
            // 创建滚动面板
            RTextScrollPane scrollPane = new RTextScrollPane(customTextArea);
            scrollPane.setFoldIndicatorEnabled(true);
            scrollPane.setLineNumbersEnabled(true);
            scrollPane.setIconRowHeaderEnabled(true);
            scrollPane.setFocusable(true);
            scrollPane.setEnabled(true);
            
            // 创建主面板
            mainPanel = new JPanel(new BorderLayout());
            mainPanel.add(scrollPane, BorderLayout.CENTER);
            mainPanel.setFocusable(true);
            mainPanel.setEnabled(true);
            
            // 优化焦点处理
            setupFocusHandling(mainPanel, scrollPane, customTextArea, isEditable);
            
            // 确保组件层次结构正确构建
            mainPanel.revalidate();
            
            // 添加文档监听器来同步内容变化（仅在编辑模式下）
            if (editorMode != EditorMode.READ_ONLY) {
                customTextArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        if (!isUpdating) {
                            updateBurpEditorContent();
                        }
                    }
                    
                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        if (!isUpdating) {
                            updateBurpEditorContent();
                        }
                    }
                    
                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        if (!isUpdating) {
                            updateBurpEditorContent();
                        }
                    }
                });
            }
        }
        
        /**
         * 配置RSyntaxTextArea的基本属性
         */
        private void configureRSyntaxTextArea(RSyntaxTextArea textArea, boolean editable) {
            // 基本编辑设置
            textArea.setEditable(editable);
            textArea.setEnabled(editable);
            textArea.setFocusable(editable);
            
            // 显示设置
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setAntiAliasingEnabled(true);
            
            // 编辑辅助功能
            textArea.setHighlightCurrentLine(true);
            textArea.setCodeFoldingEnabled(true);
            
            // 禁用可能干扰Burp编辑体验的功能
            textArea.setUseFocusableTips(false);
            textArea.setBracketMatchingEnabled(false);
            textArea.setShowMatchedBracketPopup(false);
            textArea.setAnimateBracketMatching(false);
            
            // 字体设置
            Font font = new Font("Monaco", Font.PLAIN, 12);
            textArea.setFont(font);
            
            // 制表符设置
            textArea.setTabSize(2);
            
            // 自动检测Burp主题并设置相应的编辑器主题
            if (isDarkTheme()) {
                textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                // 深色主题下的颜色设置
                textArea.setBackground(Color.decode("#252526"));
                textArea.setForeground(Color.decode("#D4D4D4"));
                textArea.setCurrentLineHighlightColor(Color.decode("#2A2D2E"));
                textArea.setSelectionColor(Color.decode("#264F78"));
            } else {
                // 浅色主题保持默认
                textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            }
            
            // 添加自定义语法高亮颜色设置
            configureCustomSyntaxHighlighting(textArea);
        }
        
        /**
         * 配置自定义语法高亮颜色设置
         */
        private void configureCustomSyntaxHighlighting(RSyntaxTextArea textArea) {
            try {
                // 获取当前的语法方案
                org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = (org.fife.ui.rsyntaxtextarea.SyntaxScheme)textArea.getSyntaxScheme().clone();
                
                // 设置默认文本颜色为黑色
                scheme.getStyle(0).foreground = Color.BLACK;
                
                // 设置JSON值的颜色为蓝色
                // 在RSyntaxTextArea中，JSON值对应的是字符串字面量
                for (int i = 0; i < scheme.getStyleCount(); i++) {
                    if (scheme.getStyle(i) != null) {
                        // 字符串字面量 - 设置为蓝色
                        if (i == org.fife.ui.rsyntaxtextarea.TokenTypes.LITERAL_STRING_DOUBLE_QUOTE) {
                            scheme.getStyle(i).foreground = new Color(0, 100, 0);
                        }
                        // 标点符号（包括{、}、:等） - 设置为黑色
                        else if (i == org.fife.ui.rsyntaxtextarea.TokenTypes.SEPARATOR ||
                                 i == org.fife.ui.rsyntaxtextarea.TokenTypes.OPERATOR) {
                            scheme.getStyle(i).foreground = Color.BLACK;
                        }
                        // 其他所有类型的文本都设置为深蓝色
                        else {
                            scheme.getStyle(i).foreground = new Color(0, 0, 139);
                        }
                    }
                }
                
                // 应用自定义语法方案
                textArea.setSyntaxScheme(scheme);
            } catch (Exception e) {
                api.logging().logToError("自定义语法高亮失败: " + e.getMessage());
            }
        }
        
        /**
         * 解决Burp与RSyntaxTextArea之间的键事件冲突问题
         * 保留基本输入功能的同时，恢复标准编辑操作（复制、粘贴、双击选中等）
         * 参考：https://github.com/bobbylight/RSyntaxTextArea/issues/269#issuecomment-776329702
         */
        private RSyntaxTextArea fixKeyEventCapture(Supplier<RSyntaxTextArea> rSyntaxTextAreaSupplier) {
            JTextComponent.removeKeymap("RTextAreaKeymap");

            RSyntaxTextArea textArea = rSyntaxTextAreaSupplier.get();

            UIManager.put("RSyntaxTextAreaUI.actionMap", null);
            UIManager.put("RSyntaxTextAreaUI.inputMap", null);
            UIManager.put("RTextAreaUI.actionMap", null);
            UIManager.put("RTextAreaUI.inputMap", null);

            return textArea;
        }
        
        /**
         * 为文本区域添加基本的编辑功能键映射
         */
        private void addBasicEditKeyBindings(RSyntaxTextArea textArea) {
            // 添加对标准字符输入的支持，解决无法输入特殊字符（如双引号）的问题
            addStandardCharacterInputSupport(textArea);
            
            // 创建一个全新的键映射，不依赖默认键映射，避免冲突
            javax.swing.text.Keymap customKeymap = JTextComponent.addKeymap("CustomRTextAreaKeymap", null);
            textArea.setKeymap(customKeymap);
            
            // 为所有上下文添加输入映射
            InputMap whenFocusedMap = textArea.getInputMap(JComponent.WHEN_FOCUSED);
            InputMap whenAncestorMap = textArea.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            InputMap whenInWindowMap = textArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap actionMap = textArea.getActionMap();

            // 1. 复制功能 - 在所有上下文中都可用
            String copyAction = "customCopy";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), copyAction);
            whenAncestorMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), copyAction);
            whenInWindowMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), copyAction);
            actionMap.put(copyAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        api.logging().logToOutput("执行复制操作");
                        // 使用系统剪贴板直接实现复制
                        String selectedText = textArea.getSelectedText();
                        if (selectedText != null) {
                            StringSelection stringSelection = new StringSelection(selectedText);
                            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(stringSelection, null);
                        }
                    } catch (Exception ex) {
                        api.logging().logToError("复制失败: " + ex.getMessage());
                    }
                }
            });

            // 2. 粘贴功能 - 在所有上下文中都可用
            String pasteAction = "customPaste";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), pasteAction);
            whenAncestorMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), pasteAction);
            whenInWindowMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), pasteAction);
            actionMap.put(pasteAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        api.logging().logToOutput("执行粘贴操作");
                        // 使用系统剪贴板直接实现粘贴
                        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                        Transferable contents = clipboard.getContents(null);
                        if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                            String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                            
                            // 处理选中内容
                            int start = textArea.getSelectionStart();
                            int end = textArea.getSelectionEnd();
                            Document doc = textArea.getDocument();
                            
                            if (start != end) {
                                // 有选中内容，先删除选中内容
                                doc.remove(start, end - start);
                            }
                            // 插入粘贴的文本
                            doc.insertString(start, text, null);
                            // 设置光标位置到粘贴文本之后
                            textArea.setCaretPosition(start + text.length());
                        }
                    } catch (Exception ex) {
                        api.logging().logToError("粘贴失败: " + ex.getMessage());
                    }
                }
            });

            // 3. 剪切功能
            String cutAction = "customCut";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK), cutAction);
            actionMap.put(cutAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        api.logging().logToOutput("执行剪切操作");
                        // 先复制再删除选中内容
                        String selectedText = textArea.getSelectedText();
                        if (selectedText != null) {
                            StringSelection stringSelection = new StringSelection(selectedText);
                            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(stringSelection, null);
                            
                            // 删除选中内容
                            int start = textArea.getSelectionStart();
                            int end = textArea.getSelectionEnd();
                            textArea.getDocument().remove(start, end - start);
                        }
                    } catch (Exception ex) {
                        api.logging().logToError("剪切失败: " + ex.getMessage());
                    }
                }
            });

            // 4. 全选功能
            String selectAllAction = "customSelectAll";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), selectAllAction);
            actionMap.put(selectAllAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        textArea.selectAll();
                    } catch (Exception ex) {
                        api.logging().logToError("全选失败: " + ex.getMessage());
                    }
                }
            });

            // 5. 删除键功能
            String deleteAction = "customDelete";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), deleteAction);
            actionMap.put(deleteAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int start = textArea.getSelectionStart();
                        int end = textArea.getSelectionEnd();
                        if (start != end) {
                            // 有选中内容，删除选中内容
                            textArea.getDocument().remove(start, end - start);
                        } else {
                            // 无选中内容，删除光标后的字符
                            int pos = textArea.getCaretPosition();
                            if (pos < textArea.getDocument().getLength()) {
                                textArea.getDocument().remove(pos, 1);
                            }
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("删除失败: " + ex.getMessage());
                    }
                }
            });

            // 6. 退格键功能
            String backspaceAction = "customBackspace";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), backspaceAction);
            actionMap.put(backspaceAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int start = textArea.getSelectionStart();
                        int end = textArea.getSelectionEnd();
                        if (start != end) {
                            // 有选中内容，删除选中内容
                            textArea.getDocument().remove(start, end - start);
                        } else {
                            // 无选中内容，删除光标前的字符
                            int pos = textArea.getCaretPosition();
                            if (pos > 0) {
                                textArea.getDocument().remove(pos - 1, 1);
                            }
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("删除失败: " + ex.getMessage());
                    }
                }
            });

            // 7. 上下左右键功能 - 光标移动
            // 上箭头
            String upAction = "customUp";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), upAction);
            actionMap.put(upAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int currentPos = textArea.getCaretPosition();
                        int line = textArea.getLineOfOffset(currentPos);
                        int column = currentPos - textArea.getLineStartOffset(line);
                        
                        if (line > 0) {
                            int prevLine = line - 1;
                            int prevLineLength = textArea.getLineEndOffset(prevLine) - textArea.getLineStartOffset(prevLine);
                            int targetColumn = Math.min(column, prevLineLength);
                            int targetPos = textArea.getLineStartOffset(prevLine) + targetColumn;
                            textArea.setCaretPosition(targetPos);
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("上箭头移动失败: " + ex.getMessage());
                    }
                }
            });

            // 下箭头
            String downAction = "customDown";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), downAction);
            actionMap.put(downAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int currentPos = textArea.getCaretPosition();
                        int line = textArea.getLineOfOffset(currentPos);
                        int column = currentPos - textArea.getLineStartOffset(line);
                        int lineCount = textArea.getLineCount();
                        
                        if (line < lineCount - 1) {
                            int nextLine = line + 1;
                            int nextLineLength = textArea.getLineEndOffset(nextLine) - textArea.getLineStartOffset(nextLine);
                            int targetColumn = Math.min(column, nextLineLength);
                            int targetPos = textArea.getLineStartOffset(nextLine) + targetColumn;
                            textArea.setCaretPosition(targetPos);
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("下箭头移动失败: " + ex.getMessage());
                    }
                }
            });

            // 左箭头
            String leftAction = "customLeft";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), leftAction);
            actionMap.put(leftAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int currentPos = textArea.getCaretPosition();
                        if (currentPos > 0) {
                            textArea.setCaretPosition(currentPos - 1);
                        }
                    } catch (Exception ex) {
                        api.logging().logToError("左箭头移动失败: " + ex.getMessage());
                    }
                }
            });

            // 右箭头
            String rightAction = "customRight";
            whenFocusedMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), rightAction);
            actionMap.put(rightAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int currentPos = textArea.getCaretPosition();
                        if (currentPos < textArea.getDocument().getLength()) {
                            textArea.setCaretPosition(currentPos + 1);
                        }
                    } catch (Exception ex) {
                        api.logging().logToError("右箭头移动失败: " + ex.getMessage());
                    }
                }
            });

            // 确保文本区域能够接收焦点
            textArea.setFocusable(true);
        }
        
        /**
         * 添加对标准字符输入的支持
         * 解决在移除默认键映射后无法输入任何字符的问题
         */
        private void addStandardCharacterInputSupport(RSyntaxTextArea textArea) {
            // 获取当前的文档
            Document doc = textArea.getDocument();
            
            // 创建一个自定义的DocumentFilter，确保所有字符都能正确插入
            DocumentFilter filter = new DocumentFilter() {
                @Override
                public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                    // 确保所有字符（包括特殊字符）都能被正确插入
                    super.insertString(fb, offset, string, attr);
                }
                
                @Override
                public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                    // 确保所有字符（包括特殊字符）都能被正确替换
                    super.replace(fb, offset, length, text, attrs);
                }
            };
            
            // 为文档设置DocumentFilter
            if (doc instanceof AbstractDocument) {
                ((AbstractDocument) doc).setDocumentFilter(filter);
            }
            
            // 使用更好的方式支持所有字符输入，包括中文和特殊字符
            InputMap inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap actionMap = textArea.getActionMap();
            
            // 添加标准文本组件的默认操作
            addDefaultTextActions(actionMap, textArea);
            
            // 为所有可打印字符添加键映射，确保键盘事件能够触发TypedAction
            // 注意：这里不需要为每个字符单独添加映射，只需确保默认的键映射正确设置
            // 为文本区域创建一个新的空Keymap并设置它
            javax.swing.text.Keymap customKeymap = JTextComponent.addKeymap("CustomRTextAreaKeymap", null);
            textArea.setKeymap(customKeymap);
            
            // 确保输入映射不为空
            if (inputMap == null) {
                inputMap = new ComponentInputMap(textArea);
                textArea.setInputMap(JComponent.WHEN_FOCUSED, inputMap);
            }
            
            // 确保文本区域能够接收焦点
            textArea.setFocusable(true);
            textArea.setFocusTraversalKeysEnabled(true);
            textArea.setRequestFocusEnabled(true);
        }
        
        /**
         * 添加标准文本组件的默认操作，支持所有字符输入
         */
        private void addDefaultTextActions(ActionMap actionMap, RSyntaxTextArea textArea) {
            // 实现插入字符的通用操作
            actionMap.put(DefaultEditorKit.defaultKeyTypedAction, new TypedAction(textArea));
            
            // 添加标准的文本编辑操作
            actionMap.put(DefaultEditorKit.cutAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    textArea.cut();
                }
            });
            actionMap.put(DefaultEditorKit.copyAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    textArea.copy();
                }
            });
            actionMap.put(DefaultEditorKit.pasteAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    textArea.paste();
                }
            });
            actionMap.put(DefaultEditorKit.selectAllAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    textArea.selectAll();
                }
            });
            actionMap.put(DefaultEditorKit.deletePrevCharAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        Document doc = textArea.getDocument();
                        int caretPos = textArea.getCaretPosition();
                        int start = textArea.getSelectionStart();
                        int end = textArea.getSelectionEnd();
                        
                        if (start != end) {
                            doc.remove(start, end - start);
                        } else if (caretPos > 0) {
                            doc.remove(caretPos - 1, 1);
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("删除前一个字符失败: " + ex.getMessage());
                    }
                }
            });
            actionMap.put(DefaultEditorKit.deleteNextCharAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        Document doc = textArea.getDocument();
                        int caretPos = textArea.getCaretPosition();
                        int start = textArea.getSelectionStart();
                        int end = textArea.getSelectionEnd();
                        
                        if (start != end) {
                            doc.remove(start, end - start);
                        } else if (caretPos < doc.getLength()) {
                            doc.remove(caretPos, 1);
                        }
                    } catch (BadLocationException ex) {
                        api.logging().logToError("删除后一个字符失败: " + ex.getMessage());
                    }
                }
            });
        }
        
        /**
         * 处理按键输入的操作，支持所有Unicode字符
         */
        private class TypedAction extends TextAction {
            private final RSyntaxTextArea textArea;
            
            public TypedAction(RSyntaxTextArea textArea) {
                super(DefaultEditorKit.defaultKeyTypedAction);
                this.textArea = textArea;
            }
            
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    // 获取输入的字符
                    String keyString = e.getActionCommand();
                    if (keyString == null || keyString.isEmpty()) {
                        return;
                    }
                    
                    char c = keyString.charAt(0);
                    
                    // 处理控制字符
                    if (c < ' ' && c != '\t' && c != '\n') {
                        return; // 忽略其他控制字符
                    }
                    
                    // 获取当前的文档和光标位置
                    Document doc = textArea.getDocument();
                    int start = textArea.getSelectionStart();
                    int end = textArea.getSelectionEnd();
                    int caretPos = textArea.getCaretPosition();
                    
                    // 如果有选中内容，先删除选中内容
                    if (start != end) {
                        doc.remove(start, end - start);
                        textArea.setCaretPosition(start);
                        caretPos = start;
                    }
                    
                    // 插入字符（支持任何Unicode字符，包括中文）
                    doc.insertString(caretPos, keyString, null);
                    
                    // 移动光标到插入字符之后
                    textArea.setCaretPosition(caretPos + keyString.length());
                } catch (Exception ex) {
                    api.logging().logToError("插入字符失败: " + ex.getMessage());
                }
            }
        }
        
        /**
         * 添加双击选中文本功能
         */
        private void addDoubleClickSelection(RSyntaxTextArea textArea) {
            textArea.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        int pos = textArea.getCaretPosition();
                        try {
                            int start = getWordStart(textArea, pos);
                            int end = getWordEnd(textArea, pos);
                            textArea.setSelectionStart(start);
                            textArea.setSelectionEnd(end);
                        } catch (BadLocationException ex) {
                            api.logging().logToError("获取单词位置失败: " + ex.getMessage());
                        }
                    }
                }
            });
        }
        
        /**
         * 获取单词的起始位置
         */
        private int getWordStart(RSyntaxTextArea textArea, int pos) throws BadLocationException {
            String text = textArea.getText(0, pos);
            int start = pos;
            while (start > 0 && !isWordSeparator(text.charAt(start - 1))) {
                start--;
            }
            return start;
        }
        
        /**
         * 获取单词的结束位置
         */
        private int getWordEnd(RSyntaxTextArea textArea, int pos) throws BadLocationException {
            String text = textArea.getText();
            int end = pos;
            while (end < text.length() && !isWordSeparator(text.charAt(end))) {
                end++;
            }
            return end;
        }
        
        /**
         * 判断字符是否为单词分隔符
         */
        private boolean isWordSeparator(char c) {
            return Character.isWhitespace(c) || 
                   c == '{' || c == '}' || c == '[' || c == ']' ||
                   c == '(' || c == ')' || c == ',' || c == ':' ||
                   c == ';' || c == '.' || c == '?' || c == '!';
        }
        
        /**
         * 设置焦点处理逻辑
         */
        private void setupFocusHandling(JPanel mainPanel, RTextScrollPane scrollPane, RSyntaxTextArea textArea, boolean editable) {
            if (!editable) return;
            
            // 添加焦点监听器，确保焦点正确传递
            mainPanel.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    SwingUtilities.invokeLater(() -> textArea.requestFocusInWindow());
                }
            });
            
            scrollPane.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    SwingUtilities.invokeLater(() -> textArea.requestFocusInWindow());
                }
            });
            
            // 设置焦点遍历键，确保可以使用Tab键导航
            mainPanel.setFocusTraversalKeysEnabled(true);
            scrollPane.setFocusTraversalKeysEnabled(true);
            textArea.setFocusTraversalKeysEnabled(true);
        }
        
        /**
         * 检测Burp是否使用深色主题
         */
        private boolean isDarkTheme() {
            try {
                // 获取Burp的UI颜色来判断主题
                Color bgColor = UIManager.getColor("Panel.background");
                // 简单的亮度检测算法
                double brightness = (bgColor.getRed() * 0.299 + bgColor.getGreen() * 0.587 + bgColor.getBlue() * 0.114) / 255;
                return brightness < 0.5; // 小于0.5认为是深色主题
            } catch (Exception e) {
                // 发生异常时默认返回浅色主题
                return false;
            }
        }
        
        /**
         * 检测内容类型并设置相应的语法高亮
         */
        private void detectContentTypeAndSetSyntax(String content) {
            if (content == null || content.trim().isEmpty()) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                return;
            }
            
            content = content.trim();
            Matcher matcher;
            
            // 检查是否为XML
            matcher = XML_PATTERN.matcher(content);
            if (matcher.find()) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
                return;
            }
            
            // 检查是否为HTML
            matcher = HTML_PATTERN.matcher(content);
            if (matcher.find()) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_HTML);
                return;
            }
            
            // 检查是否为JSON
            matcher = JSON_PATTERN.matcher(content);
            if (matcher.find() || content.contains(":")) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                return;
            }
            
            // 检查是否为JavaScript
            matcher = JS_PATTERN.matcher(content);
            if (matcher.find()) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
                return;
            }
            
            // 检查是否为CSS
            matcher = CSS_PATTERN.matcher(content);
            if (matcher.find()) {
                customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_CSS);
                return;
            }
            
            // 默认文本模式
            customTextArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        }
        
        /**
         * 同步自定义文本区域的内容到Burp的编辑器
         */
        private void updateBurpEditorContent() {
            if (requestResponse != null && customTextArea != null) {
                try {
                    isUpdating = true;
                    String content = customTextArea.getText();
                    if (content != null) {
                        // 使用RawEditor的setContents方法设置内容，确保UTF-8编码
                        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                        requestEditor.setContents(byteArray(bytes));
                    }
                } finally {
                    isUpdating = false;
                }
            }
        }
        
        /**
         * 同步Burp编辑器的内容到自定义文本区域
         */
        private void updateCustomTextAreaContent() {
            if (requestResponse != null && requestEditor.getContents() != null) {
                try {
                    isUpdating = true;
                    // 获取原始字节数组并使用UTF-8编码转换为字符串
                    byte[] bytes = requestEditor.getContents().getBytes();
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    detectContentTypeAndSetSyntax(content);
                    customTextArea.setText(content);
                } finally {
                    isUpdating = false;
                }
            }
        }

        /**
         * Check if enabled for request
         * @param requestResponse HTTP request response
         * @return Whether enabled
         */
        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return requestResponse.request().bodyToString() != null && 
                   !requestResponse.request().bodyToString().isEmpty();
        }

        /**
         * Get editor caption
         * @return Editor caption
         */
        @Override
        public String caption() {
            return "Body";
        }

        /**
         * Get UI component
         * @return UI component
         */
        @Override
        public Component uiComponent() {
            return mainPanel;
        }
        
        /**
         * Get selected data
         * @return Selected data
         */
        @Override
        public Selection selectedData() {
            // 从Burp的编辑器获取选中的数据
            return requestEditor.selection().isPresent() ? requestEditor.selection().get() : null;
        }
        
        /**
         * Check if content is modified
         * @return Whether modified
         */
        @Override
        public boolean isModified() {
            // 直接检查customTextArea的内容是否被修改
            if (requestResponse != null && customTextArea != null) {
                String originalBody = null;
                try {
                    // 获取原始字节数组并使用UTF-8编码转换为字符串
                    byte[] bytes = requestResponse.request().body().getBytes();
                    originalBody = new String(bytes, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // 如果出错，回退到默认方法
                    originalBody = requestResponse.request().bodyToString();
                }
                String currentBody = customTextArea.getText();
                return !Objects.equals(originalBody, currentBody);
            }
            return false;
        }
        
        /**
         * Get request
         * @return HTTP request
         */
        @Override
        public HttpRequest getRequest() {
            // 使用RawEditor的getContents方法获取当前内容
            return customTextArea.getText()!=null && !customTextArea.getText().isEmpty() ? 
                   requestResponse.request().withBody(byteArray(customTextArea.getText().getBytes(StandardCharsets.UTF_8))) :
                   requestResponse.request();
        }
        
        /**
         * Set request response
         * @param requestResponse HTTP request response
         */
        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.requestResponse = requestResponse;
            
            // 首先设置Burp编辑器的内容
            String body_str = null;
            ByteArray body;
            
            try {
                // 获取原始字节数组并使用UTF-8编码转换为字符串
                byte[] bytes = requestResponse.request().body().getBytes();
                body_str = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 如果出错，回退到默认方法
                body_str = requestResponse.request().bodyToString();
            }
            
            if (body_str != null) {
                // 检测内容类型
                detectContentTypeAndSetSyntax(body_str);
                
                // 尝试格式化内容
                String formattedContent = formatContent(body_str);
                
                if (formattedContent != null) {
                    body = byteArray(formattedContent.getBytes(StandardCharsets.UTF_8));
                } else {
                    // 对于无法格式化的内容，进行简单处理
                    body_str = formatFormData(body_str);
                    body = byteArray(body_str.getBytes(StandardCharsets.UTF_8));
                }
                
                // 防止触发文档监听器
                try {
                    isUpdating = true;
                    customTextArea.setText(formattedContent != null ? formattedContent : body_str);
                } finally {
                    isUpdating = false;
                }
            } else {
                body = byteArray("");
                
                try {
                    isUpdating = true;
                    customTextArea.setText("");
                } finally {
                    isUpdating = false;
                }
            }
            
            // 设置内容到Burp的编辑器，使用RawEditor的setContents方法
            requestEditor.setContents(body);
            
            // 滚动到开头
            customTextArea.setCaretPosition(0);
        }
        
        /**
         * 根据内容类型格式化内容
         */
        private String formatContent(String content) {
            // 尝试JSON格式化
            if (isJson(content)) {
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    ObjectWriter writer = objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT);
                    Object json = objectMapper.readValue(content, Object.class);
                    return writer.writeValueAsString(json);
                } catch (JsonProcessingException | IllegalArgumentException e) {
                    // JSON格式化失败
                    return null;
                }
            }
            
            // 这里可以添加其他格式的格式化逻辑，如XML、HTML等
            // 但需要引入相应的库或实现简单的格式化逻辑
            
            return null;
        }
        
        /**
         * 格式化表单数据
         */
        private String formatFormData(String content) {
            if (content.contains("&")) {
                return content.replace("&", "\n&");
            }
            return content;
        }
        
        // 检查是否为JSON
        private boolean isJson(String content) {
            if (content == null) return false;
            content = content.trim();
            return (content.startsWith("{") && content.endsWith("}")) || 
                   (content.startsWith("[") && content.endsWith("]")) ||
                   content.contains(":") && content.contains("\"");
        }
    }
}
