package cn.xinji.agentplatform.consultation.patentcomparison;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit claim numbers and conservative dependency branches. No feature or legal inference. */
public final class PatentClaimGraph {
    private static final Pattern NUMBER = Pattern.compile("(?m)^[\\t ]*(\\d{1,5})[.)][\\t ]+");
    private static final Pattern REFERENCE = Pattern.compile(
            "(?i)\\bclaims?\\s+((?:\\d+(?:\\s*(?:-|–|to)\\s*\\d+)?)(?:\\s*(?:,\\s*(?:(?:and|or)\\s+)?|(?:and|or)\\s+)\\d+(?:\\s*(?:-|–|to)\\s*\\d+)?)*)");
    private static final Pattern ITEM = Pattern.compile("(\\d+)(?:\\s*(?:-|–|to)\\s*(\\d+))?");
    private static final Pattern UNRESOLVED_REFERENCE = Pattern.compile("(?i)\\bclaims?\\b|权利要求|權利要求");
    public record Claim(int number, OriginalClaimText.Evidence original, List<Integer> alternatives) {
        public Claim { alternatives = List.copyOf(alternatives); }
    }
    public record Branch(int selectedClaim, List<Claim> inheritedClaims) {
        public Branch { inheritedClaims = List.copyOf(inheritedClaims); }
    }
    private final Map<Integer, Claim> claims;
    private PatentClaimGraph(Map<Integer, Claim> claims) { this.claims = Collections.unmodifiableMap(claims); }

    public static PatentClaimGraph parse(OriginalClaimText original) {
        if (original == null || original.text() == null || original.text().isBlank()) fail("CLAIMS_UNAVAILABLE");
        String text = original.text(); var numbers = NUMBER.matcher(text);
        List<Integer> ids = new ArrayList<>(), starts = new ArrayList<>();
        while (numbers.find()) { ids.add(Integer.parseInt(numbers.group(1))); starts.add(numbers.start()); }
        if (ids.isEmpty()) fail("CLAIM_NUMBER_CONFIRMATION_REQUIRED");
        Map<Integer, Claim> claims = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            int start = starts.get(i), end = i + 1 < ids.size() ? starts.get(i + 1) : text.length();
            String body = text.substring(start, end); int id = ids.get(i);
            if (id < 1 || claims.containsKey(id)) fail("CLAIM_NUMBER_AMBIGUOUS");
            var reference = REFERENCE.matcher(body); List<Integer> parents = new ArrayList<>();
            if (reference.find()) {
                String expression = reference.group(1); var item = ITEM.matcher(expression);
                while (item.find()) {
                    int first = Integer.parseInt(item.group(1));
                    int last = item.group(2) == null ? first : Integer.parseInt(item.group(2));
                    if (first < 1 || last < first || last - first > 4000) fail("CLAIM_REFERENCE_AMBIGUOUS");
                    for (int parent = first; parent <= last; parent++)
                        if (!parents.contains(parent)) parents.add(parent);
                }
                String prefix = body.substring(Math.max(0, reference.start() - 40), reference.start());
                boolean alternate = Pattern.compile("(?i)\\bor\\b").matcher(expression).find()
                        || Pattern.compile("(?i)(?:any\\s+one\\s+of|one\\s+of|either)\\s*$").matcher(prefix).find();
                if (reference.find()) fail("CLAIM_REFERENCE_AMBIGUOUS");
                if (parents.size() > 1 && !alternate) fail("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
            } else if (UNRESOLVED_REFERENCE.matcher(body).find()) {
                fail("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
            }
            if (Pattern.compile("权利要求|權利要求").matcher(body).find())
                fail("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
            claims.put(id, new Claim(id, original.evidence(start, end), parents));
        }
        return new PatentClaimGraph(claims);
    }

    public Collection<Claim> claims() { return claims.values(); }

    public List<Branch> branches(int selectedClaim, int maximumBranches) {
        if (maximumBranches < 1 || maximumBranches > 4000) fail("CLAIM_BRANCH_BUDGET_INVALID");
        return expand(selectedClaim, maximumBranches);
    }

    private record Frame(Claim claim, Iterator<Integer> parents) {}

    private List<Branch> expand(int selectedClaim, int limit) {
        Deque<Frame> stack = new ArrayDeque<>(); Set<Integer> visiting = new HashSet<>();
        List<Branch> paths = new ArrayList<>(); push(selectedClaim, stack, visiting);
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.claim().alternatives().isEmpty()) {
                if (paths.size() >= limit) fail("CLAIM_BRANCH_BUDGET_EXCEEDED");
                List<Claim> path = stack.stream().map(Frame::claim).toList();
                List<Claim> ordered = new ArrayList<>(path);
                for (int i = 1; i < ordered.size(); i++)
                    if (ordered.get(i - 1).number() >= ordered.get(i).number()) fail("CLAIM_REFERENCE_NOT_PRIOR");
                paths.add(new Branch(selectedClaim, ordered));
                visiting.remove(stack.pop().claim().number());
            } else if (frame.parents().hasNext()) {
                push(frame.parents().next(), stack, visiting);
            } else {
                visiting.remove(stack.pop().claim().number());
            }
        }
        return List.copyOf(paths);
    }

    private void push(int id, Deque<Frame> stack, Set<Integer> visiting) {
        Claim claim = claims.get(id); if (claim == null) fail("CLAIM_DEPENDENCY_MISSING");
        if (!visiting.add(id)) fail("CLAIM_DEPENDENCY_CYCLE");
        stack.push(new Frame(claim, claim.alternatives().iterator()));
    }

    private static void fail(String reason) { throw new IllegalArgumentException(reason); }
}
