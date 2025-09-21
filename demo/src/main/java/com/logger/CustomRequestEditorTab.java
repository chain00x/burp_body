/*
 * Copyright (c) 2022-2023. PortSwigger Ltd. All rights reserved.
 *
 * This code may be used to extend the functionality of Burp Suite Community Edition
 * and Burp Suite Professional, provided that this usage does not violate the
 * license terms for those products.
 */

 package com.logger;

 import burp.api.montoya.BurpExtension;
 import burp.api.montoya.MontoyaApi;

 public class CustomRequestEditorTab implements BurpExtension
 {
     @Override
     public void initialize(MontoyaApi api)
     {
         api.extension().setName("body");
         // 注册响应编辑器提供者，使高亮和换行功能生效
         api.userInterface().registerHttpRequestEditorProvider(new MyHttpRequestEditorProvider(api));
         api.userInterface().registerHttpResponseEditorProvider(new MyHttpResponseEditorProvider(api));
     }
 }