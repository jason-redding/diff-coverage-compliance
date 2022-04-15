package com.witcraft.diffcoveragecompliance

import java.text.ChoiceFormat
import java.text.FieldPosition
import java.text.NumberFormat
import java.util.regex.Pattern

class GrammarFormat extends ChoiceFormat {
    private static final Pattern PATTERN_GRAMMAR_REPLACEMENT_CONSTRUCT = ~/(?<!\\)\{}/
    private static final Pattern PATTERN_VALID_ESCAPE = ~'\\\\(?=[{])'
    private static final Closure<String> GRAMMAR_PATTERN_CLOSURE = { String none, String singular, String plural -> "0#${none}|1#${singular}|1<${plural}" }
    private static final Map<String, GrammarFormat> grammarCache
    private final NumberFormat numberFormat = numberInstance

    static {
        grammarCache = new HashMap<>()
    }

    GrammarFormat(String newPattern) { super(newPattern) }

    GrammarFormat(double[] limits, String[] formats) { super(limits, formats) }

    GrammarFormat(String singular, String plural) { this(plural, singular, plural) }

    GrammarFormat(String none, String singular, String plural) {
        this(GRAMMAR_PATTERN_CLOSURE.call(none, singular, plural))
    }

    static GrammarFormat grammar(String singular, String plural) { getGrammar(singular, plural) }

    static GrammarFormat grammar(String none, String singular, String plural) { getGrammar(none, singular, plural) }

    static String grammar(Number number, String singular, String plural) {
        getGrammar(singular, plural).format(number)
    }

    static String grammar(Number number, String none, String singular, String plural) {
        getGrammar(none, singular, plural).format(number)
    }

    static String grammarFormat(Number number, String singular, String plural) {
        grammar(number, singular, plural)
    }

    static String grammarFormat(Number number, String none, String singular, String plural) {
        grammar(number, none, singular, plural)
    }

    private static final GrammarFormat getGrammar(String singular, String plural) {
        getGrammar(plural, singular, plural)
    }

    private static final GrammarFormat getGrammar(String none, String singular, String plural) {
        grammarCache.computeIfAbsent(GRAMMAR_PATTERN_CLOSURE.call(none, singular, plural), format -> new GrammarFormat(format))
    }

    @Override
    StringBuffer format(double number, StringBuffer toAppendTo, FieldPosition status) {
        double[] choiceLimits = this.limits
        Object[] choiceFormats = this.formats
        // find the number
        int i
        for (i = 0; i < choiceLimits.length; ++i) {
            if (!(number >= choiceLimits[i])) {
                // same as number < choiceLimits, except catches NaN
                break
            }
        }
        --i
        if (i < 0) i = 0
        return toAppendTo.append(String.valueOf(choiceFormats[i]).replaceAll(PATTERN_GRAMMAR_REPLACEMENT_CONSTRUCT, "${numberFormat.format(number)}").replaceAll(PATTERN_VALID_ESCAPE, ''))
    }
}
