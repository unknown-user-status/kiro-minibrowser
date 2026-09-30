package com.kiro.minibrowser;

import android.webkit.JavascriptInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * JsBridge — JS interface exposed to page as `window.Android`.
 * Secure DOM helpers callable from injected scripts.
 */
public class JsBridge {
    private final ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

    /** Called from JS: Android.log("msg") — log from injected script. */
    @JavascriptInterface public void log(String msg) {
        android.util.Log.d("JsBridge", msg == null ? "" : msg);
    }

    /** Called from JS: Android.result(json) — deliver extraction result back. */
    @JavascriptInterface public void result(String json) { results.offer(json); }

    public List<String> drainResults() {
        List<String> out = new ArrayList<>();
        String r;
        while ((r = results.poll()) != null) out.add(r);
        return out;
    }

    private static String escSel(String s) {
        return s == null ? "" : s.replace("'", "\\'");
    }
    private static String escVal(String s) {
        return s == null ? "" : s.replace("'", "\\'").replace("\n", "\\n");
    }

    /** JS snippet: click first matching element. */
    public static String jsClick(String selector) {
        return "(function(){var el=document.querySelector('" + escSel(selector) + "');" +
               "if(el){el.click();return 'CLICKED:'+el.tagName;}return 'NOT_FOUND';})()";
    }

    /** JS snippet: set value of input field + fire input/change events. */
    public static String jsFill(String selector, String value) {
        return "(function(){var el=document.querySelector('" + escSel(selector) + "');" +
               "if(!el)return 'NOT_FOUND';el.focus();" +
               "el.value='" + escVal(value) + "';" +
               "el.dispatchEvent(new Event('input',{bubbles:true}));" +
               "el.dispatchEvent(new Event('change',{bubbles:true}));" +
               "return 'FILLED';})()";
    }

    /** JS snippet: extract textContent/href from all matches. */
    public static String jsExtract(String selector) {
        return "(function(){var els=document.querySelectorAll('" + escSel(selector) + "');" +
               "var out=[];for(var i=0;i<els.length;i++){" +
               "out.push({text:els[i].innerText||els[i].textContent||''," +
               "tag:els[i].tagName,href:els[i].href||''});}" +
               "return JSON.stringify(out);})()";
    }

    /** JS snippet: scroll page by pixels. */
    public static String jsScroll(int pixels) {
        return "window.scrollBy(0," + pixels + ");'SCROLLED';";
    }

    /** JS snippet: wait for element (poll 200ms, max 10s). */
    public static String jsWaitFor(String selector) {
        return "(function(){return new Promise(function(resolve){" +
               "var el=document.querySelector('" + escSel(selector) + "');" +
               "if(el){resolve('FOUND');return;}" +
               "var tries=0;var iv=setInterval(function(){" +
               "el=document.querySelector('" + escSel(selector) + "');" +
               "if(el||++tries>=50){clearInterval(iv);resolve(el?'FOUND':'TIMEOUT');}" +
               "},200);});})()";
    }
}
