package com.logger;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import static burp.api.montoya.core.ByteArray.byteArray;
import static org.fife.ui.rsyntaxtextarea.RSyntaxUtilities.getWordEnd;
import static org.fife.ui.rsyntaxtextarea.RSyntaxUtilities.getWordStart;

class MyHttpResponseEditorProvider implements HttpResponseEditorProvider {
    private final MontoyaApi api;
    
    public MyHttpResponseEditorProvider(MontoyaApi api) {
        this.api = api;
    }
    
    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        return new HighlightableHttpResponseEditor(api, creationContext);
    }
    
    // 内部类实现带高亮功能的响应编辑器
    private class HighlightableHttpResponseEditor implements ExtensionProvidedHttpResponseEditor {
        private final RSyntaxTextArea responseEditor;
        private final RTextScrollPane scrollPane;
        private HttpRequestResponse requestResponse;
        private final MontoyaApi api;
        private String currentCharset = StandardCharsets.UTF_8.name();
        
        // 添加搜索和高亮相关组件
        private JTextField searchField;
        private JButton prevButton;
        private JButton nextButton;
        private JLabel statusLabel;
        private JPanel searchPanel;
        private Highlighter.HighlightPainter highlightPainter;
        private int currentMatchIndex = -1;
        private java.util.List<Object> currentHighlights = new java.util.ArrayList<>();
        private javax.swing.Timer searchTimer;
        private JPanel mainPanel;
        
        public HighlightableHttpResponseEditor(MontoyaApi api, EditorCreationContext creationContext) {
            this.api = api;
            // 初始化编辑器组件
            responseEditor = new RSyntaxTextArea();
            responseEditor.setEditable(false);
            responseEditor.setLineWrap(true);
            responseEditor.setWrapStyleWord(true);
            responseEditor.setCodeFoldingEnabled(true);
            
            // 自定义JSON语法高亮颜色设置
            // 设置key和其他字符为黑色，value为蓝色
            try {
                // 获取当前的语法方案
                org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = (org.fife.ui.rsyntaxtextarea.SyntaxScheme)responseEditor.getSyntaxScheme().clone();
                
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
                        // 其他所有类型的文本都设置为黑色
                        else {
                            scheme.getStyle(i).foreground = new Color(0, 0, 139);
                        }
                    }
                }
                
                // 应用自定义语法方案
                responseEditor.setSyntaxScheme(scheme);
            } catch (Exception e) {
                api.logging().logToError("自定义语法高亮失败: " + e.getMessage());
            }            
            scrollPane = new RTextScrollPane(responseEditor);
            scrollPane.setFoldIndicatorEnabled(true);
            
            // 初始化高亮画笔
            highlightPainter = new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
            
            // 创建搜索面板
            createSearchPanel();
            
            // 创建主面板
            mainPanel = new JPanel(new BorderLayout());
            mainPanel.add(scrollPane, BorderLayout.CENTER);
            mainPanel.add(searchPanel, BorderLayout.SOUTH);
        }
        
        // 创建搜索面板
        private void createSearchPanel() {
            searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            searchPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            
            JLabel searchLabel = new JLabel("Search:");
            searchField = new JTextField(20);
            prevButton = new JButton("Prev");
            nextButton = new JButton("Next");
            statusLabel = new JLabel("   0 matches");
            
            searchPanel.add(searchLabel);
            searchPanel.add(searchField);
            searchPanel.add(prevButton);
            searchPanel.add(nextButton);
            searchPanel.add(statusLabel);
            
            // 添加搜索延迟，避免用户输入时频繁搜索
            searchTimer = new javax.swing.Timer(300, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    search(searchField.getText());
                }
            });
            searchTimer.setRepeats(false);
            
            // 添加文档监听器，当搜索框内容变化时触发搜索
            searchField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }
                
                @Override
                public void removeUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }
                
                @Override
                public void changedUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }
            });
            
            // 添加上下搜索按钮的点击事件
            prevButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    navigateToPreviousMatch();
                }
            });
            
            nextButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    navigateToNextMatch();
                }
            });
            
            // 添加键盘快捷键支持
            searchField.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "searchNext");
            searchField.getActionMap().put("searchNext", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    navigateToNextMatch();
                }
            });
            
            searchField.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "searchPrev");
            searchField.getActionMap().put("searchPrev", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    navigateToPreviousMatch();
                }
            });
        }
        
        // 检测响应体编码
        private String detectCharset(byte[] content) {
            try {
                CharsetDetector detector = new CharsetDetector();
                detector.setText(content);
                CharsetMatch match = detector.detect();
                if (match != null && match.getConfidence() > 50) {
                    return match.getName();
                }
            } catch (Exception e) {
                api.logging().logToError("字符集检测失败: " + e.getMessage());
            }
            return StandardCharsets.UTF_8.name();
        }
        
        // Unicode转义解码
        private String unescapeUnicode(String input) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                char c = input.charAt(i);
                if (c == '\\' && i + 5 <= input.length() && input.charAt(i+1) == 'u') {
                    // 处理Unicode转义字符
                    String hex = input.substring(i+2, i+6);
                    try {
                        int codePoint = Integer.parseInt(hex, 16);
                        sb.append((char)codePoint);
                        i += 6;
                        continue;
                    } catch (NumberFormatException e) {
                        // 转换失败，保留原始字符
                        sb.append(c);
                        i++;
                        continue;
                    }
                }
                sb.append(c);
                i++;
            }
            return sb.toString();
        }
        
        // 滚动到高亮位置
        private void scrollToHighlight(int start, int end) {
            responseEditor.setCaretPosition(start);
            responseEditor.select(start, end);
try {
    responseEditor.scrollRectToVisible(responseEditor.modelToView(start));
} catch (javax.swing.text.BadLocationException e) {
    api.logging().logToError("滚动到高亮位置失败: " + e.getMessage());
}
        }
        

        
        // 清除所有高亮
        private void clearHighlights() {
            Highlighter highlighter = responseEditor.getHighlighter();
            for (Object highlightTag : currentHighlights) {
                highlighter.removeHighlight(highlightTag);
            }
            currentHighlights.clear();
            matchPositions.clear();
        }
        
        // 重新实现搜索功能，不再依赖Highlighter.Highlight接口
        private void search(String searchText) {
            if (searchText == null || searchText.trim().isEmpty()) {
                clearHighlights();
                statusLabel.setText("   0 matches");
                currentMatchIndex = -1;
                return;
            }
            
            try {
                clearHighlights();
                String content = responseEditor.getText();
                Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(content);
                
                int matchCount = 0;
                // 存储所有匹配位置
                java.util.List<int[]> matchPositions = new java.util.ArrayList<>();
                
                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();
                    Object highlightTag = responseEditor.getHighlighter().addHighlight(start, end, highlightPainter);
                    currentHighlights.add(highlightTag);
                    matchPositions.add(new int[]{start, end});
                    matchCount++;
                }
                
                statusLabel.setText(String.format("   %d matches", matchCount));
                currentMatchIndex = matchCount > 0 ? 0 : -1;
                
                // 如果有匹配项，滚动到第一个匹配项
                if (matchCount > 0) {
                    int[] firstMatch = matchPositions.get(0);
                    scrollToHighlight(firstMatch[0], firstMatch[1]);
                }
                
                // 保存匹配位置用于导航
                this.matchPositions = matchPositions;
            } catch (Exception e) {
                api.logging().logToError("搜索失败: " + e.getMessage());
            }
        }
        
        // 添加成员变量保存匹配位置
        private java.util.List<int[]> matchPositions = new java.util.ArrayList<>();
        
        // 导航到上一个匹配项
        private void navigateToPreviousMatch() {
            if (matchPositions.isEmpty()) {
                return;
            }
            
            currentMatchIndex--;
            if (currentMatchIndex < 0) {
                currentMatchIndex = matchPositions.size() - 1;
            }
            
            int[] match = matchPositions.get(currentMatchIndex);
            scrollToHighlight(match[0], match[1]);
            
            // 更新状态栏显示当前匹配项
            statusLabel.setText(String.format("   %d/%d", currentMatchIndex + 1, matchPositions.size()));
        }
        
        // 导航到下一个匹配项
        private void navigateToNextMatch() {
            if (matchPositions.isEmpty()) {
                return;
            }
            
            currentMatchIndex++;
            if (currentMatchIndex >= matchPositions.size()) {
                currentMatchIndex = 0;
            }
            
            int[] match = matchPositions.get(currentMatchIndex);
            scrollToHighlight(match[0], match[1]);
            
            // 更新状态栏显示当前匹配项
            statusLabel.setText(String.format("   %d/%d", currentMatchIndex + 1, matchPositions.size()));
        }
        
        @Override
        public String caption() {
            return "Body";
        }
        
        @Override
        public Component uiComponent() {
            return mainPanel;
        }
        
        @Override
        public Selection selectedData() {
            int start = responseEditor.getSelectionStart();
            int end = responseEditor.getSelectionEnd();
            return start != end ? Selection.selection(start, end) : null;
        }
        
        @Override
        public boolean isModified() {
            return false; // 因为编辑器是只读的，所以永远返回false
        }
        
        @Override
        public HttpResponse getResponse() {
            return requestResponse.response();
        }
        
        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return requestResponse.response() != null && 
                   requestResponse.response().body().length() > 0;
        }
        
        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.requestResponse = requestResponse;
            Logging logging = api.logging();
            
            try {
                byte[] responseBody = requestResponse.response().body().getBytes();
                
                // 检测响应体编码
                currentCharset = detectCharset(responseBody);
                String bodyStr = new String(responseBody, Charset.forName(currentCharset));
                
                // 处理Unicode转义
                String processedStr = unescapeUnicode(bodyStr);
                
                // 检查是否为JSON并进行美化和高亮
                if (isJson(processedStr)) {
                    responseEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                    processedStr = beautifyJson(processedStr);
                } 
                // 可以添加其他类型的检测，如HTML、JavaScript等
                else if (isHtml(processedStr)) {
                    responseEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_HTML);
                } 
                else if (isJavaScript(processedStr)) {
                    responseEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
                } 
                else {
                    responseEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                }
                
                // 设置处理后的内容到编辑器
                responseEditor.setText(processedStr);
                responseEditor.setCaretPosition(0); // 滚动到开头
                
                // 清除搜索高亮
                responseEditor.getHighlighter().removeAllHighlights();
                statusLabel.setText("   0 matches");
                searchField.setText("");
                
            } catch (Exception e) {
                logging.logToError("处理响应时出错: " + e.getMessage());
                responseEditor.setText("Error processing response: " + e.getMessage());
            }
        }
        
        // 检查是否为JSON
        private boolean isJson(String content) {
            content = content.trim();
            return (content.startsWith("{") && content.endsWith("}")) || 
                   (content.startsWith("[") && content.endsWith("]"));
        }
        
        // 检查是否为HTML
        private boolean isHtml(String content) {
            content = content.trim().toLowerCase();
            return content.startsWith("<!doctype html>") || 
                   content.startsWith("<html") || 
                   content.contains("<html");
        }
        
        // 检查是否为JavaScript
        private boolean isJavaScript(String content) {
            // 简单检测，可根据需要增强
            return content.contains("function") || 
                   content.contains("var ") || 
                   content.contains("let ") || 
                   content.contains("const ");
        }
        
        // 美化JSON
        private String beautifyJson(String json) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                ObjectWriter writer = objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT);
                Object jsonObject = objectMapper.readValue(json, Object.class);
                return writer.writeValueAsString(jsonObject);
            } catch (JsonProcessingException e) {
                api.logging().logToError("JSON格式化失败: " + e.getMessage());
                return json; // 格式化失败时返回原始内容
            }
        }
    }
}