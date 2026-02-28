package com.smartgallery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java CLIP BPE tokenizer.
 *
 * Parses the tokenizer.json downloaded from Hugging Face
 * (Xenova/clip-vit-base-patch32).
 * Produces input_ids and attention_mask tensors compatible with the CLIP text
 * encoder ONNX model.
 *
 * CLIP tokenizer specifics:
 * - Vocabulary size: 49408
 * - BOS token (start of text): token ID 49406
 * - EOS token (end of text): token ID 49407
 * - Max sequence length: 77 tokens (including BOS and EOS)
 * - Uses byte-level BPE with a GPT-2 style regex for word splitting
 */
@Component
public class ClipTokenizer {

    private static final Logger log = LoggerFactory.getLogger(ClipTokenizer.class);

    // CLIP special token IDs
    public static final int BOS_TOKEN_ID = 49406;
    public static final int EOS_TOKEN_ID = 49407;
    public static final int PAD_TOKEN_ID = 0;
    public static final int MAX_LENGTH = 77;

    // Maps string token -> ID
    private Map<String, Integer> vocab;
    // Maps ID -> string token
    private Map<Integer, String> idToToken;
    // BPE merge priority map: pair -> rank (lower rank = higher priority)
    private Map<String, Integer> bpeMerges;

    // Bytes to unicode mapping used in CLIP/GPT-2 tokenizer
    private static final Map<Integer, String> BYTE_ENCODER = buildByteEncoder();
    private static final Map<String, Integer> BYTE_DECODER = buildByteDecoder();

    /**
     * GPT-2 / CLIP word-splitting regex.
     * Splits on: contractions, words, numbers, punctuation, whitespace
     */
    private static final Pattern SPLIT_PATTERN = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d|[a-zA-Z]+|[0-9]+|[^\\s\\w]",
            Pattern.CASE_INSENSITIVE);

    private boolean loaded = false;

    /**
     * Loads the tokenizer from the HuggingFace tokenizer.json file.
     * Must be called before any tokenization.
     *
     * @param tokenizerJsonPath path to the downloaded tokenizer.json
     */
    public synchronized void load(Path tokenizerJsonPath) throws IOException {
        log.info("Loading CLIP tokenizer from {}", tokenizerJsonPath);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(tokenizerJsonPath.toFile());

        // ── 1. Load vocabulary from model.vocab ──────────────────────────────
        vocab = new HashMap<>();
        idToToken = new HashMap<>();
        JsonNode vocabNode = root.path("model").path("vocab");
        Iterator<Map.Entry<String, JsonNode>> vocabIter = vocabNode.fields();
        while (vocabIter.hasNext()) {
            Map.Entry<String, JsonNode> entry = vocabIter.next();
            int id = entry.getValue().intValue();
            vocab.put(entry.getKey(), id);
            idToToken.put(id, entry.getKey());
        }

        // ── 2. Load BPE merge rules from model.merges ─────────────────────────
        bpeMerges = new HashMap<>();
        JsonNode mergesNode = root.path("model").path("merges");
        for (int i = 0; i < mergesNode.size(); i++) {
            bpeMerges.put(mergesNode.get(i).asText(), i);
        }

        loaded = true;
        log.info("CLIP tokenizer loaded: {} vocab entries, {} merge rules", vocab.size(), bpeMerges.size());
    }

    /**
     * Checks whether the tokenizer has been loaded.
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Tokenizes text into CLIP token IDs with padding/truncation to MAX_LENGTH.
     *
     * @param text the input text query
     * @return TokenOutput with input_ids [1, MAX_LENGTH] and attention_mask [1,
     *         MAX_LENGTH]
     */
    public TokenOutput tokenize(String text) {
        if (!loaded) {
            throw new IllegalStateException("Tokenizer not loaded — call load(path) first");
        }

        // Lowercase (CLIP uses lower-cased text)
        text = text.trim().toLowerCase(Locale.ROOT);

        // Split text into words using GPT-2 regex
        List<String> words = new ArrayList<>();
        Matcher matcher = SPLIT_PATTERN.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }

        // Encode each word to BPE tokens
        List<Integer> tokenIds = new ArrayList<>();
        for (String word : words) {
            // Convert word to byte-level representation
            String byteWord = wordToBytes(word);
            // Split into individual byte-tokens
            List<String> chars = new ArrayList<>();
            // The byteWord already maps each byte to its unicode character
            // We need to split it into characters used in vocab
            // For CLIP, each character in byteWord is a separate token initially
            for (int i = 0; i < byteWord.length();) {
                // Try to match multi-char tokens first (for special tokens)
                chars.add(String.valueOf(byteWord.charAt(i)));
                i++;
            }
            // Append </w> suffix to last character of word (GPT-2 style)
            if (!chars.isEmpty()) {
                chars.set(chars.size() - 1, chars.get(chars.size() - 1) + "</w>");
            }
            // Apply BPE merges
            List<String> bpeTokens = applyBpe(chars);
            for (String token : bpeTokens) {
                Integer id = vocab.get(token);
                if (id != null) {
                    tokenIds.add(id);
                }
            }
        }

        // Build final sequences: [BOS] + tokenIds (truncated) + [EOS] + [PAD...]
        long[] inputIds = new long[MAX_LENGTH];
        long[] attentionMask = new long[MAX_LENGTH];

        inputIds[0] = BOS_TOKEN_ID;
        attentionMask[0] = 1;

        int maxContentLen = MAX_LENGTH - 2; // Reserve slots for BOS and EOS
        int contentLen = Math.min(tokenIds.size(), maxContentLen);

        for (int i = 0; i < contentLen; i++) {
            inputIds[i + 1] = tokenIds.get(i);
            attentionMask[i + 1] = 1;
        }

        // EOS token
        inputIds[contentLen + 1] = EOS_TOKEN_ID;
        attentionMask[contentLen + 1] = 1;

        // Remaining positions stay 0 (PAD, mask = 0)
        return new TokenOutput(inputIds, attentionMask);
    }

    /**
     * Converts a word string to its byte-level unicode representation.
     * This is required to match the byte-level BPE vocabulary used in CLIP/GPT-2.
     */
    private String wordToBytes(String word) {
        StringBuilder sb = new StringBuilder();
        for (byte b : word.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            String mapped = BYTE_ENCODER.get(Byte.toUnsignedInt(b));
            if (mapped != null) {
                sb.append(mapped);
            }
        }
        return sb.toString();
    }

    /**
     * Applies BPE merge rules to a list of sub-tokens until no more merges can be
     * applied.
     */
    private List<String> applyBpe(List<String> tokens) {
        if (tokens.size() <= 1)
            return tokens;

        while (true) {
            // Find the highest priority (lowest rank) pair that can be merged
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < tokens.size() - 1; i++) {
                String pair = tokens.get(i) + " " + tokens.get(i + 1);
                Integer rank = bpeMerges.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }
            if (bestIdx == -1)
                break; // No more merges possible

            // Merge the best pair
            String merged = tokens.get(bestIdx) + tokens.get(bestIdx + 1);
            tokens.remove(bestIdx + 1);
            tokens.set(bestIdx, merged);
        }
        return tokens;
    }

    /**
     * Builds the byte-to-unicode encoder map used in GPT-2/CLIP BPE.
     * Maps byte values 0-255 to printable Unicode characters.
     */
    private static Map<Integer, String> buildByteEncoder() {
        Map<Integer, String> be = new LinkedHashMap<>();
        // Printable ASCII chars: !"#$%&'()*+,-./0-9:;<=>?@A-Z[\]^_`a-z{|}~
        List<Integer> bs = new ArrayList<>();
        // '!' to '~' (33 to 126)
        for (int i = '!'; i <= '~'; i++)
            bs.add(i);
        // '¡' to '¬' (161 to 172)
        for (int i = 161; i <= 172; i++)
            bs.add(i);
        // '®' to 'ÿ' (174 to 255)
        for (int i = 174; i <= 255; i++)
            bs.add(i);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                be.put(b, String.valueOf((char) (256 + n)));
                n++;
            }
        }
        for (int b : bs) {
            if (!be.containsKey(b)) {
                be.put(b, String.valueOf((char) b));
            }
        }
        return be;
    }

    private static Map<String, Integer> buildByteDecoder() {
        Map<String, Integer> bd = new HashMap<>();
        for (Map.Entry<Integer, String> entry : BYTE_ENCODER.entrySet()) {
            bd.put(entry.getValue(), entry.getKey());
        }
        return bd;
    }

    /**
     * Output of tokenization: parallel arrays for input_ids and attention_mask.
     */
    public static class TokenOutput {
        public final long[] inputIds;
        public final long[] attentionMask;

        public TokenOutput(long[] inputIds, long[] attentionMask) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
        }
    }
}
