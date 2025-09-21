/*
* Copyright (c) 2023. PortSwigger Ltd. All rights reserved.
*
* This code may be used to extend the functionality of Burp Suite Community Edition
* and Burp Suite Professional, provided that this usage does not violate the
* license terms for those products.
*/

package com.logger;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import static burp.api.montoya.core.ByteArray.byteArray;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;



class MyExtensionProvidedHttpRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final RawEditor requestEditor;

    private HttpRequestResponse requestResponse;
    private final MontoyaApi api;

    MyExtensionProvidedHttpRequestEditor(MontoyaApi api, EditorCreationContext creationContext) {
        this.api = api;
        if (creationContext.editorMode() == EditorMode.READ_ONLY) {
            requestEditor = api.userInterface().createRawEditor(EditorOptions.READ_ONLY);
        } else {
            requestEditor = api.userInterface().createRawEditor();
        }
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (requestResponse.request().bodyToString() != null && requestResponse.request().bodyToString() != "") {
            return true;
        } else {
            return false;
        }

    }

    @Override
    public String caption() {
        return "Body";
    }

    @Override
    public Component uiComponent() {
        return requestEditor.uiComponent();
    }
    

    @Override
    public Selection selectedData() {
        return requestEditor.selection().isPresent() ? requestEditor.selection().get() : null;
    }

    @Override
    public boolean isModified() {
        return requestEditor.isModified();
    }

    @Override
    public HttpRequest getRequest() {
        HttpRequest request;
        if (requestEditor.isModified()) {
            request = requestResponse.request().withBody(requestEditor.getContents());
        } else {
            request = requestResponse.request();
        }
        return request;
    }
    
    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.requestResponse = requestResponse;
        String body_str = requestResponse.request().bodyToString();
        String beautifiedJson = "";
        Logging logging = api.logging();
        ByteArray body;
        if ((body_str.contains("\":\""))) {
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectWriter writer = objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT);
            Object json;
            try {
                json = objectMapper.readValue(body_str, Object.class);
                beautifiedJson = writer.writeValueAsString(json);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            body = byteArray(beautifiedJson.getBytes(StandardCharsets.UTF_8));
        } else {
            body_str = body_str.replace("&", "\n&");
            body = byteArray(body_str);
        }
        this.requestEditor.setContents(body);
    }
}