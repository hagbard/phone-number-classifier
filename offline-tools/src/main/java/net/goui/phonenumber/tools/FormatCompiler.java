package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec.FormatTemplate;
import net.goui.phonenumber.proto.Metadata;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FormatCompiler {
    // Byte:
    // Literal (ASCII):   [ 0 x x x x x x x ] >= 0x20  --> individual separators + parens.
    // Group:             [ 0 0 0 S S L L L ] < 0x20   --> bare group (Sep + Len)
    //                    [ 1 0 x T T L L L ]          --> special group (Type + Len); optional,
    // ignored, parens
    //                    [ 1 1 x x x x x x ]          --> national prefix, carrier code, future
    // extension
    // Length: 000 -> 1, 111 -> 8 (value + 1)
    // Sep:  0 -> Nothing,  1 -> Space,  2 -> Hyphen,  3 -> RESERVED
    // Type: 0 -> Optional, 1 -> Parens, 2 -> Ignored, 3 -> RESERVED
    //
    // XXX-...     -> Group(3, HYPHEN)
    // (XXX)...    -> Special(3, PARENS)
    // XX***-...   -> [ Group(2, HYPHEN), Special(3, OPTIONAL) ]
    // {XX>abc}... -> [ Special(2, IGNORE), 'a', 'b', 'c' ]
    // #...        -> NatPrefix()
    // (#XXX)...   -> [ '(', NatPrefix(), Group(3, NONE), ')' ]  (could do better)
    // (XX @)-...   -> [ '(', Group(2, SPACE), CarrierCode(), ')' ]

    private static class SpecMatcher {
        private final Pattern pattern;
        private final BiConsumer<MatchResult, ByteArrayOutputStream> handler;

        SpecMatcher(String regex, BiConsumer<MatchResult, ByteArrayOutputStream> handler) {
            this.pattern = Pattern.compile(regex);
            this.handler = handler;
        }

        String apply(String spec, ByteArrayOutputStream out) {
            Matcher matcher = pattern.matcher(spec);
            if (!matcher.lookingAt()) {
                return null;
            }
            MatchResult r = matcher.toMatchResult();
            handler.accept(r, out);
            return spec.substring(r.end());
        }
    }

    private static final ImmutableList<SpecMatcher> SPEC_MATCHERS =
            ImmutableList.of(
                    new SpecMatcher(
                            "(X{1,8})([- ])?",
                            (r, out) -> {
                                int len = r.group(1).length() - 1;
                                int sep = r.group(1) != null ? " -".indexOf(r.group(1).charAt(0)) + 1 : 0;
                                out.write((sep << 3) + len);
                            }),
                    new SpecMatcher(
                            "(\\*{1,8})",
                            (r, out) -> {
                                int len = r.group(1).length() - 1;
                                out.write(0x80 + len);
                            }),
                    new SpecMatcher(
                            "\\((X{1,8})\\)",
                            (r, out) -> {
                                int len = r.group(1).length() - 1;
                                out.write(0x80 + ((0x1) << 3) + len);
                            }),
                    new SpecMatcher(
                            "\\{(X{1,8})>([^}]*)}",
                            (r, out) -> {
                                int len = r.group(1).length() - 1;
                                out.write(0x80 + ((0x2) << 3) + len);
                                r.group(2)
                                        .codePoints()
                                        .peek(c -> checkState(0x20 <= c && c < 0x80))
                                        .forEach(c -> out.write((byte) (c & 0x7F)));
                            }),
                    new SpecMatcher(
                            ".",
                            (r, out) -> {
                                int c = r.group().codePointAt(0);
                                checkState(0x20 <= c && c < 0x80);
                                out.write((byte) (c & 0x7F));
                            }));

    // (#XXX) XX**-XXX
    // XXX {XX>56}-XXX
    //
    // Separators -,_,/,0
    // Parens - maybe just separators, but very common.
    // Basic group (never longer than 6 outside of last group)
    // Group + Opt (2 groups?)
    // Group replacement (never opt, Argentina only atm) - super special case.
    // National prefix or carrier code (maybe in parens): (#XX)-... XX (@)...
    // Carrier code
    //
    //
    //
    // Separators: '-', ' ', <OPT>, '/', 0
    //
    public static String compileSpec(FormatTemplate format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        String formatSpec = format.getSpecifier();
        while (!formatSpec.isEmpty()) {
            for (SpecMatcher m : SPEC_MATCHERS) {
                String s = m.apply(formatSpec, out);
                if (s != null) {
                    formatSpec = s;
                    break;
                }
            }
        }
        return Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    private static String toBase64(Metadata.MatcherDataProto proto) {
        return Base64.getEncoder()
                .withoutPadding()
                .encodeToString(proto.getMatcherData().toByteArray());
    }
}
