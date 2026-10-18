package com.github.catvod.js;

import com.github.catvod.js.utils.Parser;
import com.fongmi.quickjs.JSArray;
import com.fongmi.quickjs.JSMethod;
import com.fongmi.quickjs.QuickJSContext;

import java.lang.reflect.Method;
import java.util.List;

public class Function {

    private final QuickJSContext ctx;
    private final Parser parser;

    public Function(QuickJSContext ctx) {
        this.parser = new Parser();
        this.ctx = ctx;
        setProperty();
    }

    private void setProperty() {
        for (Method method : getClass().getMethods()) {
            if (!method.isAnnotationPresent(JSMethod.class)) continue;
            ctx.getGlobalObject().setProperty(method.getName(), args -> {
                try {
                    return method.invoke(this, args);
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }

    private JSArray toArray(List<String> items) {
        JSArray array = ctx.createNewJSArray();
        for (int i = 0; i < items.size(); i++) array.set(items.get(i), i);
        return array;
    }

    @JSMethod
    public String pd(String html, String rule, String urlKey) {
        return parser.parseDomForUrl(html, rule, urlKey);
    }

    @JSMethod
    public String pdfh(String html, String rule) {
        return parser.parseDomForUrl(html, rule, "");
    }

    @JSMethod
    public JSArray pdfa(String html, String rule) {
        return toArray(parser.parseDomForArray(html, rule));
    }

    @JSMethod
    public JSArray pdfl(String html, String rule, String texts, String urls, String urlKey) {
        return toArray(parser.parseDomForList(html, rule, texts, urls, urlKey));
    }
}
