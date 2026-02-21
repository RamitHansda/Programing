package tri;

import java.io.*;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Instructions to candidate.
 *  1) Run this code in the REPL to observe its behaviour. The execution entry point is main().
 *  2) Provide an implementation of the findAll method in MyPrefixSearch.
 *  3) Describe any trade-offs arising from your implementation.
 *  4) If time permits, try to improve your implementation.
 */

class PrefixSearch {

    static class MyPrefixSearch {
        // Note: Any indexed solution should be more performant on repeat calls.
        // Trade-offs arise on how to store the index efficiently while maintaining fast lookup.
        // This solution is indexed using a trie, which is also space efficient for certain use cases.
        // For a light discussion on tries and other alternative implementations see:
        //    https://www.toptal.com/java/the-trie-a-neglected-data-structure

        private MyTrie _index = new MyTrie(null);

        MyPrefixSearch( String document )
        {
            buildIndex(document);
        }

        private void buildIndex( String document )
        {
            Integer location = 0;
            String words[] = document.split(" ");
            for(String word : words){
                if(word.length() > 0 ) // could be an extra whitespace, leading to a "" token
                {
                    String clean = word.toLowerCase().replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]","");
                    _index.add(clean, location);
                }
                location += word.length() + 1;
            }
        }

        /*
         * findAll: Return a list of all locations in a document where the
         * (case insensitive) word begins with the given prefix.
         *
         * Example:  given the document "a aa Aaa abca bca",
         *   1) findAll("a")   -> [0, 2, 5, 9]
         *   2) findAll("bc")  -> [14]
         *   3) findAll("aA")  -> [2, 5]
         *   4) findAll("abc") -> [9]
         *
         **/
        public List<Integer> findAll( String prefix )
        {
            return _index.get(prefix);
        }

        class MyTrie {
            Character _character;
            private List<Integer> _locations;
            private Map<Character, MyTrie> _nodes = new HashMap<>();

            MyTrie(Character character){
                _character = character;
                _locations = new LinkedList<Integer>();
            }

            // record a substring location.
            public void add(String chars, Integer location){
                if(_character != null)
                    // note: this trie variant records at each node, not just leaf nodes (simpler, less space efficient).
                    _locations.add(location);
                if(chars.length() > 0){
                    char c = chars.charAt(0);
                    _nodes.putIfAbsent(c, new MyTrie(c));
                    _nodes.get(c).add(chars.substring(1), location);
                }
            }

            // retrieve locations for substring
            public List<Integer> get(String prefix){
                if(prefix.length() > 0){
                    char c = prefix.charAt(0);
                    if(_nodes.containsKey(c))
                        return _nodes.get(c).get(prefix.substring(1));
                    else
                        return new LinkedList<Integer>();
                }
                else{
                    return _locations;
                }
            }

        }
    }


    /*********  Tests  *********/

    /*
     * doTestsPass
     * Validate that the prefix search returns the correct results for the sample document.
     */
    public static void doTestsPass() {
        MyPrefixSearch prefixSearch = new MyPrefixSearch(_document);

        BiFunction<List<?>, List<?>, Boolean> resultMatches = (actual, expected) ->
                actual != null && expected.equals(actual);

        if( resultMatches.apply(
                prefixSearch.findAll("demonstrate"), Arrays.asList( 80 ))
                && resultMatches.apply(prefixSearch.findAll("pub"), Arrays.asList( 3, 988 ))
                && resultMatches.apply(prefixSearch.findAll("publishing"), Arrays.asList( 3, 988 ))
                && resultMatches.apply(prefixSearch.findAll("lab"), Arrays.asList( 1173, 1263, 1517 ))
                && resultMatches.apply(prefixSearch.findAll("laborum"), Arrays.asList( 1517 ))
                && resultMatches.apply(prefixSearch.findAll("in"),
                Arrays.asList( 0, 404, 717, 839, 857, 873, 930, 1159, 1334, 1351, 1468))
                && resultMatches.apply(prefixSearch.findAll("lor"),
                Arrays.asList( 34, 434, 456, 686, 1061, 1080 ))
                && resultMatches.apply(prefixSearch.findAll("l"),
                Arrays.asList( 34, 309, 434, 456, 557, 651, 686, 806, 1061, 1080, 1173, 1263, 1517))
                && prefixSearch.findAll("").size() == 0
                && prefixSearch.findAll("hamburger").size() == 0)
            System.out.println("All tests pass");
        else
            System.out.println("Test failed");
    }

    public static void main(String[] args) {
        doTestsPass();
    }

    private static final String _document = String.join("",
            "In publishing and graphic design, lorem ipsum is a ",
            "filler text commonly used to demonstrate the graphic elements of a ",
            "document or visual presentation. Replacing meaningful content that ",
            "could be distracting with placeholder text may allow viewers to focus ",
            "on graphic aspects such as font, typography, and page layout. It also ",
            "reduces the need for the designer to come up with meaningful text, as ",
            "they can instead use hastily generated lorem ipsum text. The lorem ",
            "ipsum text is typically a scrambled section of De finibus bonorum et ",
            "malorum, a 1st-century BC Latin text by Cicero, with words altered, ",
            "added, and removed to make it nonsensical, improper Latin. A variation ",
            "of the ordinary lorem ipsum text has been used in typesetting since ",
            "the 1960s or earlier, when it was popularized by advertisements for ",
            "Letraset transfer sheets. It was introduced to the Information Age in ",
            "the mid-1980s by Aldus Corporation, which employed it in graphics and ",
            "word processing templates for its desktop publishing program, ",
            "PageMaker, for the Apple Macintosh. A common form of lorem ipsum ",
            "reads: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do ",
            "eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad ",
            "minim veniam, quis nostrud exercitation ullamco laboris nisi ut ",
            "aliquip ex ea commodo consequat. Duis aute irure dolor in ",
            "reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla ",
            "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in ",
            "culpa qui officia deserunt mollit anim id est laborum.");
}