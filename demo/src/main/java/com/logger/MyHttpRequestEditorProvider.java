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
