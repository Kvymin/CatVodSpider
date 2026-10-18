package com.github.catvod.js;

import com.fongmi.quickjs.JSArray;
import com.fongmi.quickjs.JSObject;
import com.fongmi.quickjs.QuickJSContext;
import com.github.catvod.js.utils.Parser;

import java.util.List;

public class Function {

    private final QuickJSContext ctx;
    private final Parser parser;

    public Function(QuickJSContext ctx) {
        this.parser = new Parser();
        this.ctx = ctx;
        bindFunctions();
    }

    private void bindFunctions() {
        JSObject global = ctx.getGlobalObject();
        global.setProperty("pd", args -> pd((String) args[0], (String) args[1], (String) args[2]));
        global.setProperty("pdfh", args -> pdfh((String) args[0], (String) args[1]));
        global.setProperty("pdfa", args -> pdfa((String) args[0], (String) args[1]));
        global.setProperty("pdfl", args -> pdfl((String) args[0], (String) args[1], (String) args[2], (String) args[3], (String) args[4]));
    }

    private JSArray toArray(List<String> items) {
        JSArray array = ctx.createNewJSArray();
        for (int i = 0; i < items.size(); i++) array.set(items.get(i), i);
        return array;
    }

    public String pd(String html, String rule, String urlKey) {
        return parser.parseDomForUrl(html, rule, urlKey);
    }

    public String pdfh(String html, String rule) {
        return parser.parseDomForUrl(html, rule, "");
    }

    public JSArray pdfa(String html, String rule) {
        return toArray(parser.parseDomForArray(html, rule));
    }

    public JSArray pdfl(String html, String rule, String texts, String urls, String urlKey) {
        return toArray(parser.parseDomForList(html, rule, texts, urls, urlKey));
    }
}
