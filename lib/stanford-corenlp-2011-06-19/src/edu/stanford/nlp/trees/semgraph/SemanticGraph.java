package edu.stanford.nlp.trees.semgraph;

import edu.stanford.nlp.ling.CoreAnnotations.CurrentAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.Morphology;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.StringParsingTask;
import edu.stanford.nlp.util.StringUtils;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.DirectedMultigraph;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a semantic graph of a sentence or document, with IndexedWord
 * objects for nodes.
 * <p>
 * Note that given the kinds of representations that we normally use with
 * typedDependenciesCollapsed that there can be (small) cycles in a
 * SemanticGraph, and these cycles may involve the node that is conceptually the
 * root of the graph, so there may be no node without a parent node. You can
 * better get at the root(s) via the variable and methods provided.
 *
 * @author Christopher Cox
 * @author Teg Grenager
 * @see SemanticGraphEdge
 * @see IndexedWord
 */
public class SemanticGraph extends DirectedMultigraph<IndexedWord, SemanticGraphEdge> {

  public static boolean addSRLArcs = false;

  private static final SemanticGraphFormatter formatter = new SemanticGraphFormatter();

  /**
   * The distinguished root vertices, if known.
   */
  private Collection<IndexedWord> roots;

  private List<IndexedWord> vlist;

  /**
   * This returns an ordered list of vertices (based upon their indices in the
   * sentence). NOTE: This presumes the graph is static, and any updates to the
   * graph after this call will not reflect the modified graph.
   *
   * @return Ordered, read-only list of vertices
   */
  public List<IndexedWord> vertexList() {
    if (vlist == null) {
      Set<IndexedWord> s = vertexSet();
      vlist = new ArrayList<IndexedWord>(s);
      Collections.sort(vlist);
      vlist = Collections.unmodifiableList(vlist);
    }
    return vlist;
  }

  /**
   * Returns an ordered list of vertices, based upon their indices in the
   * sentence. If refresh is true, this obtains an updated view on the vertices
   * and saves it to cache. Otherwise returns the cached set of ordered
   * vertices.
   *
   * @return ordered read-only list of vertices
   */
  public synchronized List<IndexedWord> vertexList(boolean refresh) {
    if (refresh || vlist == null) {
      Set<IndexedWord> s = vertexSet();
      vlist = new ArrayList<IndexedWord>(s);
      Collections.sort(vlist);
      vlist = Collections.unmodifiableList(vlist);
    }
    return vlist;
  }

  private List<SemanticGraphEdge> elist;

  /**
   * Returns an ordered list of edges in the graph. Note that this presumes that
   * the edges will not change after the first call to edgeList.
   */
  public List<SemanticGraphEdge> edgeList() {
    if (elist == null) {
      Set<SemanticGraphEdge> s = edgeSet();
      elist = new ArrayList<SemanticGraphEdge>(s);
    }
    Collections.sort(elist, SemanticGraphEdge.orderByTargetComparator());
    return elist;
  }

  public List<SemanticGraphEdge> edgeList(boolean refresh) {
    if (refresh || elist == null) {
      Set<SemanticGraphEdge> s = edgeSet();
      elist = new ArrayList<SemanticGraphEdge>(s);
      Collections.sort(elist, SemanticGraphEdge.orderByTargetComparator());
    }
    return elist;
  }

  public boolean isEmpty() {
    return edgeSet().isEmpty();
  }

  /**
   * Searches up to 2 levels to determine how far ancestor is from child (i.e.,
   * returns 1 if "ancestor" is a parent, or 2 if ancestor is a grandparent.
   *
   * @param child
   *          candidate child
   * @param ancestor
   *          candidate ancestor
   * @return the number of generations between "child" and "ancestor" (1 is an
   *         immediate parent), or -1 if there is no relationship found.
   */
  public int isAncestor(IndexedWord child, IndexedWord ancestor) {

    List<IndexedWord> parents = this.getParentList(child);
    if (parents == null) {
      return -1;
    }
    if (parents.contains(ancestor)) {
      return 1;
    }
    for (IndexedWord parent : parents) {
      List<IndexedWord> grandparents = this.getParentList(parent);
      if (grandparents.contains(ancestor)) {
        return 2;
      }
    }
    return -1;
  }

  /**
   * Return the maximum distance to a least common ancestor. We only search as
   * high as grandparents. We return -1 if no common parent or grandparent is
   * found.
   *
   * @return The maximum distance to a least common ancestor.
   */
  public int commonAncestor(IndexedWord v1, IndexedWord v2) {
    List<IndexedWord> v1Parents = this.getParentList(v1);
    List<IndexedWord> v2Parents = this.getParentList(v2);
    List<IndexedWord> v1GrandParents = new ArrayList<IndexedWord>();
    List<IndexedWord> v2GrandParents = new ArrayList<IndexedWord>();
    // does v1 have any parents that are v2's parents?

    for (IndexedWord v1Parent : v1Parents) {
      if (v2Parents.contains(v1Parent)) {
        return 1;
      }
      v1GrandParents.addAll(this.getParentList(v1Parent));
    }
    // does v1 have any grandparents that are v2's parents?
    for (IndexedWord v1GrandParent : v1GrandParents) {
      if (v2Parents.contains(v1GrandParent)) {
        return 2;
      }
    }
    // build v2 grandparents
    for (IndexedWord v2Parent : v2Parents) {
      v2GrandParents.addAll(this.getParentList(v2Parent));
    }
    // does v1 have any parents or grandparents that are v2's grandparents?
    for (IndexedWord v2GrandParent : v2GrandParents) {
      if (v1Parents.contains(v2GrandParent)) {
        return 1;
      }
      if (v1GrandParents.contains(v2GrandParent)) {
        return 2;
      }
    }
    return -1;
  }

  /**
   * Returns the least common ancestor. We only search as high as grandparents.
   * We return null if no common parent or grandparent is found. Any of the
   * input words can also be the answer if one is the parent or grandparent of
   * other
   *
   * @return The least common ancestor.
   */
  public IndexedWord getCommonAncestor(IndexedWord v1, IndexedWord v2) {
    if (this.isAncestor(v1, v2) >= 1) {
      return v2;
    }

    if (this.isAncestor(v2, v1) >= 1) {
      return v1;
    }

    List<IndexedWord> v1Parents = this.getParentList(v1);
    List<IndexedWord> v2Parents = this.getParentList(v2);
    List<IndexedWord> v1GrandParents = new ArrayList<IndexedWord>();
    List<IndexedWord> v2GrandParents = new ArrayList<IndexedWord>();
    // does v1 have any parents that are v2's parents?

    for (IndexedWord v1Parent : v1Parents) {
      if (v2Parents.contains(v1Parent)) {
        return v1Parent;
      }
      v1GrandParents.addAll(this.getParentList(v1Parent));
    }
    // does v1 have any grandparents that are v2's parents?
    for (IndexedWord v1GrandParent : v1GrandParents) {
      if (v2Parents.contains(v1GrandParent)) {
        return v1GrandParent;
      }
    }
    // build v2 grandparents
    for (IndexedWord v2Parent : v2Parents) {
      v2GrandParents.addAll(this.getParentList(v2Parent));
    }
    // does v1 have any parents or grandparents that are v2's grandparents?
    for (IndexedWord v2GrandParent : v2GrandParents) {
      if (v1Parents.contains(v2GrandParent)) {
        return v2GrandParent;
      }
      if (v1GrandParents.contains(v2GrandParent)) {
        return v2GrandParent;
      }
    }
    return null;
  }

  // if "det" is true, the search for a child is restricted to the "determiner"
  // grammatical relation
  public boolean matchPatternToVertex(String pattern, IndexedWord vertex, boolean det) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    String pat = pattern.replaceAll("<", ",<");
    pat = pat.replaceAll(">", ",>");
    String[] nodePath = pat.split(",");
    for (String s : nodePath) {
      if (s.equals("")) {
        continue;
      }
      String word = s.substring(1);
      char dir = s.charAt(0);
      if (dir == '<') {
        // look for a matching parent
        List<IndexedWord> parents = getParentList(vertex);
        boolean match = false;
        for (IndexedWord parent : parents) {
          String lemma = parent.get(LemmaAnnotation.class);
          if (lemma.equals(word)) {
            match = true;
            break;
          }
        }
        if (!match) {
          return false;
        }
      } else if (dir == '>') {
        if (det) {
          // look for a matching child with "det" relation
          List<IndexedWord> children = getChildrenWithReln(vertex, EnglishGrammaticalRelations.DETERMINER);
          children.addAll(getChildrenWithReln(vertex, EnglishGrammaticalRelations.PREDETERMINER));
          boolean match = false;
          for (IndexedWord child : children) {
            String lemma = child.get(LemmaAnnotation.class);
            if (lemma.equals("")) {
              lemma = child.word().toLowerCase();
            }
            if (lemma.equals(word)) {
              match = true;
              break;
            }
          }
          if (!match) {
            return false;
          }
        } else {// take any relation, except "det"
          List<Pair<GrammaticalRelation, IndexedWord>> children = childPairs(vertex);
          boolean match = false;
          for (Pair<GrammaticalRelation, IndexedWord> pair : children) {
            if (pair.first().toString().equals("det"))
              continue;
            IndexedWord child = pair.second();
            String lemma = child.get(LemmaAnnotation.class);
            if (lemma.equals("")) {
              lemma = child.word().toLowerCase();
            }
            if (lemma.equals(word)) {
              match = true;
              break;
            }
          }
          if (!match) {
            return false;
          }
        }
      } else {
        throw new RuntimeException("Warning: bad pattern \"%s\"\n" + pattern);
      }
    }
    return true;
  }

  // if "det" is true, the search for a child is restricted to the "determiner"
  // grammatical relation
  public boolean matchPatternToVertex(String pattern, IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    String pat = pattern.replaceAll("<", ",<");
    pat = pat.replaceAll(">", ",>");
    String[] nodePath = pat.split(",");
    for (String s : nodePath) {
      if (s.equals("")) {
        continue;
      }
      String word = s.substring(1);
      char dir = s.charAt(0);
      if (dir == '<') {
        // look for a matching parent
        List<IndexedWord> parents = getParentList(vertex);
        boolean match = false;
        for (IndexedWord parent : parents) {
          String lemma = parent.get(LemmaAnnotation.class);
          if (lemma.equals(word)) {
            match = true;
            break;
          }
        }
        if (!match) {
          return false;
        }
      } else if (dir == '>') {
        // look for a matching child
        List<IndexedWord> children = getChildList(vertex);
        boolean match = false;
        for (IndexedWord child : children) {
          String lemma = (child.get(LemmaAnnotation.class) == null ? "" : child.get(LemmaAnnotation.class));
          if (lemma.equals("")) {
            lemma = child.word().toLowerCase();
          }
          if (lemma.equals(word)) {
            match = true;
            break;
          }
        }
        if (!match) {
          return false;
        }
      } else {
        throw new RuntimeException("Warning: bad pattern \"%s\"\n" + pattern);
      }
    }
    return true;
  }

  public List<IndexedWord> getChildList(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      System.err.println("Weird vertex: Index " + vertex.index() + ", word " + vertex.word());
      System.err.println("Vertices I know: " + vertexSet());
      throw new IllegalArgumentException();
    }
    List<IndexedWord> result = new ArrayList<IndexedWord>(getChildren(vertex));
    Collections.sort(result);
    return result;
  }

  public Collection<IndexedWord> getChildren(IndexedWord vertex) {
    List<IndexedWord> result = new ArrayList<IndexedWord>();
    Set<SemanticGraphEdge> outbound = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : outbound) {
      IndexedWord child = edge.getTarget();
      result.add(child);
    }
    return result;
  }

  public boolean hasChildren(IndexedWord vertex) {
    return outgoingEdgesOf(vertex).size() > 0;
  }

  public List<SemanticGraphEdge> getIncomingEdgesSorted(IndexedWord vertex) {
    List<SemanticGraphEdge> edges = new ArrayList<SemanticGraphEdge>(incomingEdgesOf(vertex));
    Collections.sort(edges);
    return edges;
  }

  public List<SemanticGraphEdge> getOutEdgesSorted(IndexedWord vertex) {
    List<SemanticGraphEdge> edges = new ArrayList<SemanticGraphEdge>(outgoingEdgesOf(vertex));
    Collections.sort(edges);
    return edges;
  }

  public List<IndexedWord> getParentList(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    List<IndexedWord> result = new ArrayList<IndexedWord>(getParents(vertex));
    Collections.sort(result);
    return result;
  }

  public Collection<IndexedWord> getParents(IndexedWord vertex) {
    List<IndexedWord> result = new ArrayList<IndexedWord>();
    Set<SemanticGraphEdge> inbound = incomingEdgesOf(vertex);
    for (SemanticGraphEdge edge : inbound) {
      IndexedWord parent = edge.getSource();
      result.add(parent);
    }
    return result;
  }

  /**
   * Method for getting the siblings of a particular node. Siblings are the
   * other children of your parent, where parent is determined as the parent
   * returned by getParent
   *
   * @return collection of sibling nodes (does not include vertex)
   *         the collection is empty if your parent is null
   */
  public Collection<IndexedWord> getSiblings(IndexedWord vertex) {
    IndexedWord parent = this.getParent(vertex);
    if(parent != null) {
      List<IndexedWord> result = this.getChildList(parent);
      result.remove(vertex);//remove this vertex - you're not your own sibling
      return result;
    }
    else return new ArrayList<IndexedWord>();
  }

  /*
   * Helper function for the public function with the same name.
   */

  private List<IndexedWord> getPathToRoot(IndexedWord vertex, List<IndexedWord> used) {
    used.add(vertex);

    List<IndexedWord> parents = getParentList(vertex);

    parents.removeAll(used);

    if (roots.contains(vertex) || (parents.size() == 0)) {
      used.remove(used.size() - 1);
      if (roots.contains(vertex))
        return new Vector<IndexedWord>();
      else
        return null; // no path found
    }

    for (IndexedWord parent : parents) {
      List<IndexedWord> path = getPathToRoot(parent, used);
      if (path != null) {
        path.add(0, parent);
        used.remove(used.size() - 1);
        return path;
      }
    }

    used.remove(used.size() - 1);
    return null;

  }

  /*
   * Find the path from the given node to a root; the path does not include the
   * given node. Returns an empty list if vertex is a root. Returns null if a
   * root is unaccessible (should never happen).
   */

  public List<IndexedWord> getPathToRoot(IndexedWord vertex) {
    return getPathToRoot(vertex, new Vector<IndexedWord>());
  }

  /*
   * Return the real syntactic parent of vertex
   */

  public IndexedWord getParent(IndexedWord vertex) {
    List<IndexedWord> path = getPathToRoot(vertex);

    if (path != null && path.size() > 0)
      return path.get(0);
    else
      return null;
  }

  /**
   * Returns the <em>first</em> {@link edu.stanford.nlp.ling.IndexedWord
   * IndexedWord} in this {@code SemanticGraph} having the given integer index,
   * or throws {@code IllegalArgumentException} if no such node is found.
   */
  public IndexedWord getNodeByIndex(int index) throws IllegalArgumentException {
    IndexedWord node = getNodeByIndexSafe(index);
    if (node == null)
      throw new IllegalArgumentException("No SemanticGraph vertex with index " + index);
    else
      return node;
  }

  /**
   * Same as above, but returns {@code null} if the index does not exist
   * (instead of throwing an exception).
   */
  public IndexedWord getNodeByIndexSafe(int index) {
    for (IndexedWord vertex : vertexList()) {
      if (vertex.index() == index) {
        return vertex;
      }
    }
    return null;
  }

  /**
   * Returns the <i>first</i> {@link edu.stanford.nlp.ling.IndexedWord
   * IndexedWord} in this <code>SemanticGraph</code> having the given word or
   * regex, or return null if no such found.
   */
  public IndexedWord getNodeByWordPattern(String pattern) throws IllegalArgumentException {
    Pattern p = Pattern.compile(pattern);
    for (IndexedWord vertex : vertexList()) {
      String w = vertex.word();
      if ((w == null && pattern == null) || w != null && p.matcher(w).matches()) {
        return vertex;
      }
    }
    return null;
    // throw new
    // IllegalArgumentException("No SemanticGraph vertex with word pattern " +
    // pattern);
  }

  /**
   * Returns all nodes of type {@link edu.stanford.nlp.ling.IndexedWord
   * IndexedWord} in this <code>SemanticGraph</code> having the given word or
   * regex, or returns empty list if no such found.
   */
  public List<IndexedWord> getAllNodesByWordPattern(String pattern) throws IllegalArgumentException {
    Pattern p = Pattern.compile(pattern);
    List<IndexedWord> nodes = new ArrayList<IndexedWord>();
    for (IndexedWord vertex : vertexList()) {
      String w = vertex.word();
      if ((w == null && pattern == null) || w != null && p.matcher(w).matches()) {
        nodes.add(vertex);
      }
    }
    return nodes;
    // throw new
    // IllegalArgumentException("No SemanticGraph vertex with word pattern " +
    // pattern);
  }

  /**
   * Returns the set of descendants governed by this node in the graph.
   *
   */
  public Set<IndexedWord> descendants(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    // Do a depth first search
    Set<IndexedWord> descendantSet = new HashSet<IndexedWord>();
    descendantsHelper(vertex, descendantSet);
    return descendantSet;
  }

  private void descendantsHelper(IndexedWord curr, Set<IndexedWord> descendantSet) {
    if (descendantSet.contains(curr)) {
      return;
    }
    descendantSet.add(curr);
    for (IndexedWord child : getChildList(curr)) {
      descendantsHelper(child, descendantSet);
    }
  }

  /**
   * Returns a list of pairs of a relation name and the child
   * IndexedFeatureLabel that bears that relation.
   */
  public List<Pair<GrammaticalRelation, IndexedWord>> childPairs(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    List<Pair<GrammaticalRelation, IndexedWord>> childPairs = Generics.newArrayList();
    for (SemanticGraphEdge e : edges) {
      childPairs.add(new Pair<GrammaticalRelation, IndexedWord>(e.getRelation(), e.getTarget()));
    }
    return childPairs;
  }

  /**
   * Returns a list of pairs of a relation name and the parent
   * IndexedFeatureLabel to which we bear that relation.
   */
  public List<Pair<GrammaticalRelation, IndexedWord>> parentPairs(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Collection<SemanticGraphEdge> edges = incomingEdgesOf(vertex);
    List<Pair<GrammaticalRelation, IndexedWord>> parentPairs = Generics.newArrayList();
    for (SemanticGraphEdge e : edges) {
      parentPairs.add(new Pair<GrammaticalRelation, IndexedWord>(e.getRelation(), e.getSource()));
    }
    return parentPairs;
  }

  /**
   * Returns a set of relations which this node has with its parents.
   *
   * @return The set of relations which this node has with its parents.
   */
  public Set<GrammaticalRelation> relns(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Set<GrammaticalRelation> relns = Generics.newHashSet();
    List<Pair<GrammaticalRelation, IndexedWord>> pairs = parentPairs(vertex);
    for (Pair<GrammaticalRelation, IndexedWord> p : pairs) {
      relns.add(p.first());
    }
    return relns;
  }

  /**
   * Returns the relation that node a has with node b.
   *
   */
  public GrammaticalRelation reln(IndexedWord a, IndexedWord b) {
    if (!vertexSet().contains(a)) {
      throw new IllegalArgumentException();
    }

    List<Pair<GrammaticalRelation, IndexedWord>> pairs = childPairs(a);
    for (Pair<GrammaticalRelation, IndexedWord> p : pairs)
      if (p.second().equals(b))
        return p.first();

    return null;
  }

  /**
   * Returns a list of relations which this node has with its children.
   */
  public Set<GrammaticalRelation> childRelns(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Set<GrammaticalRelation> relns = Generics.newHashSet();
    List<Pair<GrammaticalRelation, IndexedWord>> pairs = childPairs(vertex);
    for (Pair<GrammaticalRelation, IndexedWord> p : pairs) {
      relns.add(p.first());
    }
    return relns;
  }

  public Collection<IndexedWord> getRoots() {
    return roots;
  }

  /**
   * Initially looks for nodes which have no incoming arcs. If there are any, it
   * returns a list of them. If not, it looks for nodes from which every other
   * node is reachable. If there are any, it returns a list of them. Otherwise,
   * it returns an empty list.
   *
   * @return A list of root nodes or an empty list.
   */
  public List<IndexedWord> getVerticesWithoutParents() {
    List<IndexedWord> result = new ArrayList<IndexedWord>();
    Set<IndexedWord> vertices = vertexSet();
    for (IndexedWord v : vertices) {
      int inDegree = inDegreeOf(v);
      if (inDegree == 0) {
        result.add(v);
      }
    }
    Collections.sort(result);
    return result;
  }

  /** Returns the (first) root of this SemanticGraph. */
  public IndexedWord getFirstRoot() {
    if (roots.isEmpty())
      throw new RuntimeException("No roots in graph:\n" + this
          + "\nFind where this graph was created and make sure you're adding roots.");
    return roots.iterator().next();
  }

  public void addRoot(IndexedWord root) {
    addVertex(root);
    roots.add(root);
  }

  /**
   * Recomputes the roots, based off of actual candidates. This is done to
   * ensure a rooted tree after a sequence of edits. If the none of the vertices
   * can act as a root (due to a cycle), keep old rootset, retaining only the
   * existing vertices on that list.
   *
   * TODO: this cannot deal with "Hamburg is a city which everyone likes", as
   * the intended root node,'Hamburg, is also the dobj of the relative clause. A
   * possible solution would be to create edgeset routines that allow filtering
   * over a predicate, and specifically filter out dobj relations for choosing
   * next best candidate. This could also be useful for dealing with
   * non-syntactic arcs in the future. TODO: There is also the possibility the
   * roots could be empty at the end, and will need to be resolved. TODO:
   * determine if this is a reasonably correct solution.
   */
  public void resetRoots() {
    Collection<IndexedWord> newRoots = getVerticesWithoutParents();
    if (newRoots.size() > 0) {
      roots.clear();
      roots.addAll(newRoots);
      return;
    }

    /*
     * else { Collection<IndexedWord> oldRoots = new
     * ArrayList<IndexedWord>(roots); for (IndexedWord oldRoot : oldRoots) { if
     * (!containsVertex(oldRoot)) removeVertex(oldRoot); } }
     */

    // If no apparent root candidates are available, likely due to loop back
    // edges (rcmod), find the node that dominates the most nodes, and let
    // that be the new root. Note this implementation epitomizes K.I.S.S., and
    // is brain dead and inoptimal, and will require further work.
    ClassicCounter<Pair<IndexedWord, IndexedWord>> nodeDists = new ClassicCounter<Pair<IndexedWord, IndexedWord>>();
    TreeSet<IndexedWord> nodes = new TreeSet<IndexedWord>(vertexSet());

    for (IndexedWord node1 : nodes) {
      for (IndexedWord node2 : nodes) {
        // want directed paths only
        Pair<IndexedWord, IndexedWord> key = new Pair<IndexedWord, IndexedWord>(node1, node2);
        List<SemanticGraphEdge> path = getShortestDirectPathEdges(node1, node2);
        if (path != null) {
          int dist = path.size();
          nodeDists.setCount(key, dist);
        }
      }
    }

    // K.I.S.S. alg: just sum up and see who's on top, values don't have much
    // meaning outside
    // of determining dominance.
    ClassicCounter<IndexedWord> dominatedEdgeCount = new ClassicCounter<IndexedWord>();
    TreeSet<IndexedWord> nodesList = new TreeSet<IndexedWord>(vertexSet());
    for (IndexedWord outer : nodesList) {
      for (IndexedWord inner : nodesList) {
        Pair<IndexedWord, IndexedWord> key = new Pair<IndexedWord, IndexedWord>(outer, inner);
        dominatedEdgeCount.incrementCount(outer, nodeDists.getCount(key));
      }
    }

    IndexedWord winner = Counters.argmax(dominatedEdgeCount); // TODO: account
                                                              // for multiply
                                                              // rooted graphs
                                                              // later
    setRoot(winner);

  }

  public void setRoot(IndexedWord word) {
    roots.clear();
    roots.add(word);
  }

  public void setRoots(Collection<IndexedWord> words) {
    roots.clear();
    roots.addAll(words);
  }

  /**
   * Call this after performing a sequence of edits on vertices in the graph.
   * Because JGrapht maintains a set of indices based upon the vertices
   * themselves, any modifications to them will result in those indices being
   * invalidated, resulting in errors. SemanticGraphEdges maintain references to
   * the actual vertices themselves, and this uses those to recreate the indices
   * by pulling the edges and vertices out, and putting them back in (similar to
   * pulling electronic components out of the board and placing them back in, or
   * reseating them, to ensure the connections are proper).
   *
   * TODO: delete this, as invalidated by updated JGraph
   */
  /*
   * public synchronized void reseat() { List<IndexedFeatureLabel> vertices =
   * new ArrayList<IndexedFeatureLabel>(vertexSet()); List<SemanticGraphEdge>
   * edges = new ArrayList<SemanticGraphEdge>(edgeSet());
   *
   * for (IndexedFeatureLabel vertex1 : vertices) { for (IndexedFeatureLabel
   * vertex2 : vertices) { if (vertex1 != vertex2) removeEdge(vertex1, vertex2);
   * } }
   *
   * for (IndexedFeatureLabel vertex : vertices) { removeVertex(vertex); }
   *
   * // for (SemanticGraphEdge edge : edges) { // removeEdge(edge); // } for
   * (IndexedFeatureLabel vertex : vertices) { addVertex(vertex); } for
   * (SemanticGraphEdge edge : edges) { addEdge(edge.getCachedSourceRef(),
   * edge.getCachedTargetRef(), edge.getRelation(), edge.getWeight()); }
   * edgeList(true); vertexList(true); }
   */

  /**
   *
   * @return a sorted list of the vertices
   * @throws Exception
   *           if this graph is not a DAG
   */
  public List<IndexedWord> topologicalSort() throws Exception {
    LinkedList<IndexedWord> q = new LinkedList<IndexedWord>(getVerticesWithoutParents());
    Set<SemanticGraphEdge> remainingEdges = new HashSet<SemanticGraphEdge>(edgeSet());
    List<IndexedWord> result = new ArrayList<IndexedWord>();
    while (!q.isEmpty()) {
      IndexedWord node = q.removeLast();
      // System.err.println("Outputting node: " + node);
      result.add(node);
      Set<SemanticGraphEdge> outbound = outgoingEdgesOf(node);
      for (SemanticGraphEdge e : outbound) {
        IndexedWord target = e.getTarget();
        // System.err.println("Considering node: " + target);
        remainingEdges.remove(e);
        Set<SemanticGraphEdge> inc = incomingEdgesOf(target);
        boolean hasInbound = false;
        for (SemanticGraphEdge other : inc) {
          if (remainingEdges.contains(other)) {
            hasInbound = true;
            // System.err.println("Other inbound edge found: " + other);
            break;
          }
        }
        if (!hasInbound) {
          q.addLast(target); // this guy can be put on the queue, because he has
                             // no more outstanding parents!
          // System.err.println("Adding to queue: " + target);
        } else {
          // System.err.println("Can't add to queue: " + target);
        }
      }
    }
    if (result.size() != vertexSet().size())
      throw new Exception("This graph has cycles. Topological sort not possible: " + result);
    return result;
  }

  public boolean hasChild(IndexedWord vertex, GrammaticalRelation reln, String childLemma) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        if (edge.getTarget().get(LemmaAnnotation.class).equals(childLemma))
          return true;
      }
    }
    return false;
  }

  public boolean hasChildWithReln(IndexedWord vertex, GrammaticalRelation reln) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if vertex has an incoming relation reln
   *
   * @param vertex
   *          a node in this graph
   * @param reln
   *          the relation we want to check
   * @return true if vertex has an incoming relation reln
   */
  public boolean hasParentWithReln(IndexedWord vertex, GrammaticalRelation reln) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    Collection<SemanticGraphEdge> edges = incomingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the first IndexedFeatureLabel bearing a certain grammatical
   * relation, or null if none.
   */
  public IndexedWord getChildWithReln(IndexedWord vertex, GrammaticalRelation reln) {
    if (vertex.equals(IndexedWord.NO_WORD))
      return null;
    if (!vertexSet().contains(vertex))
      throw new IllegalArgumentException();

    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        return edge.getTarget();
      }
    }
    return null;
  }

  /**
   * Returns a list of all parents bearing a certain grammatical relation, or an
   * empty list if none.
   */
  public List<IndexedWord> getParentsWithReln(IndexedWord vertex, GrammaticalRelation reln) {
    if (vertex.equals(IndexedWord.NO_WORD))
      return new ArrayList<IndexedWord>();
    if (!vertexSet().contains(vertex))
      throw new IllegalArgumentException();

    List<IndexedWord> parentList = Generics.newArrayList();
    Collection<SemanticGraphEdge> edges = incomingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        parentList.add(edge.getSource());
      }
    }
    return parentList;
  }

  /**
   * Returns a list of all children bearing a certain grammatical relation, or
   * an empty list if none.
   */
  public List<IndexedWord> getChildrenWithReln(IndexedWord vertex, GrammaticalRelation reln) {
    if (vertex.equals(IndexedWord.NO_WORD))
      return new ArrayList<IndexedWord>();
    if (!vertexSet().contains(vertex))
      throw new IllegalArgumentException();

    List<IndexedWord> childList = Generics.newArrayList();
    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (edge.getRelation().equals(reln)) {
        childList.add(edge.getTarget());
      }
    }
    return childList;
  }

  /**
   * Returns a list of all children bearing one of a set of grammatical
   * relations, or an empty list if none.
   *
   * NOTE: this will only work for relation types that are classes. Those that
   * are collapsed are currently not handled correctly since they are identified
   * by strings.
   */
  public List<IndexedWord> getChildrenWithRelns(IndexedWord vertex, Collection<GrammaticalRelation> relns) {
    if (vertex.equals(IndexedWord.NO_WORD))
      return new ArrayList<IndexedWord>();
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    List<IndexedWord> childList = new ArrayList<IndexedWord>();
    Collection<SemanticGraphEdge> edges = outgoingEdgesOf(vertex);
    for (SemanticGraphEdge edge : edges) {
      if (relns.contains(edge.getRelation())) {
        childList.add(edge.getTarget());
      }
    }
    return childList;
  }

  /**
   * Given a governor, dependent, and the relation between them, returns the
   * SemanticGraphEdge object of that arc if it exists, otherwise returns null.
   */
  public SemanticGraphEdge getEdge(IndexedWord gov, IndexedWord dep, GrammaticalRelation reln) {
    Collection<SemanticGraphEdge> edges = getAllEdges(gov, dep);
    if (edges != null) {
      for (SemanticGraphEdge edge : edges) {
        if (!edge.getSource().equals(gov))
          continue;
        if ((edge.getRelation().equals(reln))) {
          return edge;
        }
      }
    }
    return null;
  }

  public boolean isNegatedVertex(IndexedWord vertex) {
    if (vertex == IndexedWord.NO_WORD) {
      return false;
    }
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException("Vertex " + vertex + " not in graph " + this);
    }

    return hasChildWithReln(vertex, EnglishGrammaticalRelations.NEGATION_MODIFIER)
        || hasChild(vertex, GrammaticalRelation.DEPENDENT, "nor");
  }

  private boolean isNegatedVerb(IndexedWord vertex) {
    if (!vertexSet().contains(vertex)) {
      throw new IllegalArgumentException();
    }
    return (vertex.tag().startsWith("VB") && isNegatedVertex(vertex));
  }

  /**
   * Check if the vertex is in a "conditional" context. Right now it's only
   * returning true if vertex has an "if" marker attached to it, i.e. the vertex
   * is in a clause headed by "if".
   */
  public boolean isInConditionalContext(IndexedWord vertex) {
    List<IndexedWord> children = this.getChildrenWithReln(vertex, EnglishGrammaticalRelations.MARKER);
    for (IndexedWord child : children) {
      if (child.word().equalsIgnoreCase("if"))
        return true;
    }
    return false;
  }

  // Obsolete; use functions in rte.feat.NegPolarityFeaturizers instead

  public boolean attachedNegatedVerb(IndexedWord vertex) {
    for (IndexedWord parent : getParentList(vertex)) {
      if (isNegatedVerb(parent)) {
        return true;
      }
    }
    return false;
  }

  // Returns true iff this vertex stands in the "aux" relation to (any of)
  // its parent(s).
  public boolean isAuxiliaryVerb(IndexedWord vertex) {
    Set<GrammaticalRelation> relns = relns(vertex);
    if (relns.isEmpty())
      return false;

    boolean result = relns.contains(EnglishGrammaticalRelations.AUX_MODIFIER)
        || relns.contains(EnglishGrammaticalRelations.AUX_PASSIVE_MODIFIER);
    // System.err.println("I say " + vertex + (result ? " is" : " is not") +
    // " an aux");
    return result;
  }

  public Set<IndexedWord> getLeafVertices() {
    Set<IndexedWord> result = new HashSet<IndexedWord>();
    Set<IndexedWord> vertices = vertexSet();
    for (IndexedWord v : vertices) {
      int outDegree = outDegreeOf(v);
      if (outDegree == 0) {
        result.add(v);
      }
    }
    return result;
  }

  public int size() {
    return this.vertexSet().size();
  }

  /**
   * @return true if the graph contains no cycles.
   */
  public boolean isDag() {
    Set<IndexedWord> unused = new HashSet<IndexedWord>(vertexSet());
    while (!unused.isEmpty()) {
      IndexedWord arbitrary = unused.iterator().next();
      boolean result = isDagHelper(arbitrary, unused, new HashSet<IndexedWord>());
      if (result) {
        return false;
      }
    }
    return true;
  }

  private boolean isDagHelper(IndexedWord current, Set<IndexedWord> unused, Set<IndexedWord> trail) {
    if (trail.contains(current)) {
      return true;
    } else if (!unused.contains(current)) {
      return false;
    }
    unused.remove(current);
    trail.add(current);
    for (IndexedWord child : getChildList(current)) {
      boolean result = isDagHelper(child, unused, trail);
      if (result) {
        return true;
      }
    }

    trail.remove(current);
    return false;
  }

  /**
   * Inserts the given specific portion of an uncollapsed relation back into the
   * targetList
   *
   * @param specific
   *          Specific relation to put in.
   * @param relnTgtNode
   *          Node governed by the uncollapsed relation
   * @param tgtList
   *          Target List of words
   */
  private void insertSpecificIntoList(String specific, IndexedWord relnTgtNode, List<IndexedWord> tgtList) {
    int currIndex = tgtList.indexOf(relnTgtNode);
    Set<IndexedWord> descendents = descendants(relnTgtNode);
    IndexedWord specificNode = new IndexedWord();
    specificNode.set(LemmaAnnotation.class, specific);
    specificNode.set(TextAnnotation.class, specific);
    specificNode.set(CurrentAnnotation.class, specific);
    while ((currIndex >= 1) && descendents.contains(tgtList.get(currIndex - 1))) {
      currIndex--;
    }
    tgtList.add(currIndex, specificNode);
  }

  // ============================================================================
  // String display
  // ============================================================================
  /**
   * Recursive depth first traversal. Returns a structured representation of the
   * dependency graph.
   *
   * Example:
   * <p/>
   *
   * <pre>
   *  /-> need-3 (root)
   *    -> We-0 (nsubj)
   *    -> do-1 (aux)
   *    -> n't-2 (neg)
   *    -> badges-6 (dobj)
   *      -> no-4 (det)
   *      -> stinking-5 (amod)
   * </pre>
   */
  @Override
  public String toString() {
    Collection<IndexedWord> rootNodes = getRoots();
    if (rootNodes.isEmpty()) {
      // Shouldn't happen, but return something!
      return toString("readable");
    }

    StringBuilder sb = new StringBuilder();
    Set<IndexedWord> used = new HashSet<IndexedWord>();
    for (IndexedWord root : rootNodes) {
      sb.append("-> ").append(root).append(" (root)\n");
      recToString(root, sb, 1, used);
    }
    Set<IndexedWord> nodes = new HashSet<IndexedWord>(vertexSet());
    nodes.removeAll(used);
    while (!nodes.isEmpty()) {
      IndexedWord node = nodes.iterator().next();
      sb.append(node).append("\n");
      recToString(node, sb, 1, used);
      nodes.removeAll(used);
    }
    return sb.toString();
  }

  // helper for toString()
  private void recToString(IndexedWord curr, StringBuilder sb, int offset, Set<IndexedWord> used) {
    used.add(curr);
    List<SemanticGraphEdge> edges;
    try {
      edges = new ArrayList<SemanticGraphEdge>(outgoingEdgesOf(curr));
    } catch (IllegalArgumentException iae) {
      // this seems to happen with graphs from degenerate trees (e.g. (S1 (S (NP (NN INTRODUCTION)))))
      // in this case, there are no outgoing edges
      edges = new ArrayList<SemanticGraphEdge>();
    }
    Collections.sort(edges);
    for (SemanticGraphEdge edge : edges) {
      IndexedWord target = edge.getTarget();
      sb.append(space(2 * offset)).append("-> ").append(target).append(" (").append(edge.getRelation()).append(")\n");
      if (!used.contains(target)) { // recurse
        recToString(target, sb, offset + 1, used);
      }
    }
  }

  private static String space(int width) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < width; i++) {
      b.append(" ");
    }
    return b.toString();
  }

  public String toRecoveredSentenceString() {
    StringBuilder sb = new StringBuilder();
    boolean pastFirst = false;
    for (IndexedWord word : vertexList()) {
      if (pastFirst) {
        sb.append(' ');
      }
      pastFirst = true;
      sb.append(word.word());
    }
    return sb.toString();
  }

  public String toRecoveredSentenceStringWithIndexMarking() {
    StringBuilder sb = new StringBuilder();
    boolean pastFirst = false;
    int index = 0;
    for (IndexedWord word : vertexList()) {
      if (pastFirst) {
        sb.append(' ');
      }
      pastFirst = true;
      sb.append(word.word());
      sb.append("(");
      sb.append(index++);
      sb.append(")");
    }
    return sb.toString();
  }

  /**
   * Similar to <code>toRecoveredString</code>, but will fill in words that were
   * collapsed into relations (i.e. prep_for --> 'for'). Mostly to deal with
   * collapsed dependency trees.
   *
   * TODO: consider merging with toRecoveredString() NOTE: assumptions currently
   * are for English. NOTE: currently takes immediate successors to current word
   * and expands them. This assumption may not be valid for other conditions or
   * languages?
   */
  public String toEnUncollapsedSentenceString() {
    List<IndexedWord> uncompressedList = Generics.newLinkedList(vertexSet());
    List<Pair<String, IndexedWord>> specifics = Generics.newArrayList();

    // Collect the specific relations and the governed nodes, and then process
    // them one by one,
    // to avoid concurrent modification exceptions.
    for (IndexedWord word : vertexSet()) {
      for (SemanticGraphEdge edge : incomingEdgesOf(word)) {
        GrammaticalRelation relation = edge.getRelation();
        // Extract the specific: need to account for possiblity that relation
        // can
        // be a String or GrammaticalRelation (how did it happen this way?)
        String specific = relation.getSpecific();

        if (specific == null) {
          if (edge.getRelation().equals(EnglishGrammaticalRelations.AGENT)) {
            specific = "by";
          }
        }

        // Insert the specific at the leftmost token that is not governed by
        // this node.
        if (specific != null) {
          Pair<String, IndexedWord> specPair = new Pair<String, IndexedWord>(specific, word);
          specifics.add(specPair);
        }
      }
    }

    for (Pair<String, IndexedWord> tuple : specifics) {
      insertSpecificIntoList(tuple.first(), tuple.second(), uncompressedList);
    }

    return StringUtils.join(uncompressedList, " ");
  }

  /**
   * Returns a String representation of the result of this set of typed
   * dependencies in a user-specified format. Currently, three formats are
   * supported:
   * <dl>
   * <dt>"plain"</dt>
   * <dd>(Default.) Formats the dependencies as logical relations, as
   * exemplified by the following:
   *
   * <pre>
   *  nsubj(died-1, Sam-0)
   *  tmod(died-1, today-2)
   * </pre>
   *
   * </dd>
   * <dt>"readable"</dt>
   * <dd>Formats the dependencies as a table with columns <code>dependent</code>, <code>relation</code>, and <code>governor</code>, as exemplified by the
   * following:
   *
   * <pre>
   *  Sam-0               nsubj               died-1
   *  today-2             tmod                died-1
   * </pre>
   *
   * </dd>
   * <dt>"xml"</dt>
   * <dd>Formats the dependencies as XML, as exemplified by the following:
   *
   * <pre>
   *  &lt;dependencies&gt;
   *    &lt;dep type="nsubj"&gt;
   *      &lt;governor idx="1"&gt;died&lt;/governor&gt;
   *      &lt;dependent idx="0"&gt;Sam&lt;/dependent&gt;
   *    &lt;/dep&gt;
   *    &lt;dep type="tmod"&gt;
   *      &lt;governor idx="1"&gt;died&lt;/governor&gt;
   *      &lt;dependent idx="2"&gt;today&lt;/dependent&gt;
   *    &lt;/dep&gt;
   *  &lt;/dependencies&gt;
   * </pre>
   *
   * </dd>
   * </dl>
   *
   * @param format
   *          a <code>String</code> specifying the desired format
   * @return a <code>String</code> representation of the typed dependencies in
   *         this <code>GrammaticalStructure</code>
   */
  public String toString(String format) {
    if (format != null && format.equals("xml")) {
      return toXMLString();
    } else if (format != null && format.equals("readable")) {
      return toReadableString();
    } else {
      return toList();
    }
  }

  /**
   * Returns a String representation of this graph as a list of typed
   * dependencies, as exemplified by the following:
   *
   * <pre>
   *  nsubj(died-6, Sam-3)
   *  tmod(died-6, today-9)
   * </pre>
   *
   * @return a <code>String</code> representation of this set of typed
   *         dependencies
   */
  public String toList() {
    StringBuilder buf = new StringBuilder();
    for (SemanticGraphEdge edge : this.edgeList()) {
      buf.append(edge.getRelation().toString()).append("(");
      buf.append(toDepStyle(edge.getSource())).append(",");
      buf.append(toDepStyle(edge.getTarget())).append(")\n");
    }
    return buf.toString();
  }

  /**
   * Similar to toList(), but uses POS tags instead of word and index.
   */
  public String toPOSList() {
    StringBuilder buf = new StringBuilder();
    for (SemanticGraphEdge edge : this.edgeList()) {
      buf.append(edge.getRelation().toString()).append("(");
      buf.append(toPOSStyle(edge.getSource())).append(",");
      buf.append(toPOSStyle(edge.getTarget())).append(")\n");
    }
    return buf.toString();
  }

  private static String toDepStyle(IndexedWord fl) {
    StringBuilder buf = new StringBuilder();
    buf.append(fl.word());
    buf.append("-");
    buf.append(fl.index());
    return buf.toString();
  }

  private static String toPOSStyle(IndexedWord fl) {
    StringBuilder buf = new StringBuilder();
    buf.append(fl.word());
    buf.append("/");
    buf.append(fl.tag());
    buf.append("-");
    buf.append(fl.index());
    return buf.toString();
  }

  private String toReadableString() {
    StringBuilder buf = new StringBuilder();
    buf.append(String.format("%-20s%-20s%-20s%n", "dep", "reln", "gov"));
    buf.append(String.format("%-20s%-20s%-20s%n", "---", "----", "---"));
    for (IndexedWord root : getRoots()) {
      buf.append(String.format("%-20s%-20s%-20s%n", toDepStyle(root), "root", "root"));
    }
    for (SemanticGraphEdge edge : this.edgeList()) {
      buf.append(String.format("%-20s%-20s%-20s%n", toDepStyle(edge.getTarget()), edge.getRelation().toString(),
          toDepStyle(edge.getSource())));
    }
    return buf.toString();
  }

  private String toXMLString() {
    StringBuilder buf = new StringBuilder("<dependencies style=\"typed\">\n");
    for (SemanticGraphEdge edge : this.edgeList()) {
      String reln = edge.getRelation().toString();
      String gov = (edge.getSource()).word();
      int govIdx = (edge.getSource()).index();
      String dep = (edge.getTarget()).word();
      int depIdx = (edge.getTarget()).index();
      buf.append("  <dep type=\"").append(reln).append("\">\n");
      buf.append("    <governor idx=\"").append(govIdx).append("\">").append(gov).append("</governor>\n");
      buf.append("    <dependent idx=\"").append(depIdx).append("\">").append(dep).append("</dependent>\n");
      buf.append("  </dep>\n");
    }
    buf.append("</dependencies>\n");
    return buf.toString();
  }

  public String toCompactString() {
    return toCompactString(false);
  }

  public String toCompactString(boolean showTags) {
    StringBuilder sb = new StringBuilder();
    Set<IndexedWord> used = new HashSet<IndexedWord>();
    Collection<IndexedWord> roots = getRoots();
    if (roots.isEmpty()) {
      if (size() == 0) {
        return "[EMPTY_SEMANTIC_GRAPH]";
      } else {
        return "[UNROOTED_SEMANTIC_GRAPH]";
      }
      // return toString("readable");
    }
    for (IndexedWord root : roots) {
      toCompactStringHelper(root, sb, used, showTags);
    }
    return sb.toString();
  }

  private void toCompactStringHelper(IndexedWord node, StringBuilder sb, Set<IndexedWord> used, boolean showTags) {
    used.add(node);
    try {
      boolean isntLeaf = (outDegreeOf(node) > 0);
      if (isntLeaf) {
        sb.append("[");
      }
      sb.append(node.word());
      if (showTags) {
        sb.append("/");
        sb.append(node.tag());
      }
      for (SemanticGraphEdge edge : getOutEdgesSorted(node)) {
        IndexedWord target = edge.getTarget();
        sb.append(" ").append(edge.getRelation()).append(":");
        if (!used.contains(target)) { // avoid infinite loop
          toCompactStringHelper(target, sb, used, showTags);
        } else {
          sb.append(target.word());
          if (showTags) {
            sb.append("/");
            sb.append(target.tag());
          }
        }
      }
      if (isntLeaf) {
        sb.append("]");
      }
    } catch (IllegalArgumentException e) {
      System.err.println("WHOA!  SemanticGraph.toCompactStringHelper() ran into problems at node " + node);
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Returns a <code>String</code> representation of this semantic graph,
   * formatted by the default semantic graph formatter.
   */
  public String toFormattedString() {
    return formatter.formatSemanticGraph(this);
  }

  /**
   * Returns a <code>String</code> representation of this semantic graph,
   * formatted by the supplied semantic graph formatter.
   */
  public String toFormattedString(SemanticGraphFormatter formatter) {
    return formatter.formatSemanticGraph(this);
  }

  /**
   * Pretty-prints this semantic graph to <code>System.out</code>, formatted by
   * the supplied semantic graph formatter.
   */
  public void prettyPrint(SemanticGraphFormatter formatter) {
    System.out.println(formatter.formatSemanticGraph(this));
  }

  /**
   * Pretty-prints this semantic graph to <code>System.out</code>, formatted by
   * the default semantic graph formatter.
   */
  public void prettyPrint() {
    System.out.println(formatter.formatSemanticGraph(this));
  }

  /**
   * Don't use this!
   */
  @Override
  @Deprecated
  public SemanticGraphEdge addEdge(IndexedWord s, IndexedWord d) {
    throw new UnsupportedOperationException("Use the format with more parameters.");
  }

  public SemanticGraphEdge addEdge(IndexedWord s, IndexedWord d, GrammaticalRelation reln, double weight) {
    // SemanticGraphEdge e = super.addEdge(s, d);
    // e.setRelation(reln);
    // e.setWeight(weight);
    // return e;

    SemanticGraphEdge newEdge = new SemanticGraphEdge(s, d, reln, weight);
    super.addEdge(s, d, newEdge);
    return newEdge;
  }

  // =======================================================================

  /**
   * Tries to parse a String representing a SemanticGraph. Right now it's fairly
   * dumb, could be made more sophisticated.
   * <p/>
   *
   * Example: "[ate subj:Bill dobj:[muffins nn:blueberry]]"
   * <p/>
   *
   * This is the same format generated by toCompactString().
   */
  public static SemanticGraph valueOf(String s) {
    return (new SemanticGraphParsingTask(s)).parse();
  }

  public SemanticGraph() {
    super(new SemanticGraphEdge.Factory());
    roots = new HashSet<IndexedWord>();
  }

  /**
   * Returns a new SemanticGraph which is a copy of the supplied SemanticGraph.
   * Both the nodes ({@link IndexedWord}s) and the edges (SemanticGraphEdges)
   * are copied.
   */
  public SemanticGraph(SemanticGraph g) {
    super(g.getEdgeFactory());
    Collection<IndexedWord> oldRoots = new ArrayList<IndexedWord>(g.getRoots());
    Set<IndexedWord> vertexes = g.vertexSet();
    HashMap<IndexedWord, IndexedWord> prevToNewMap = new HashMap<IndexedWord, IndexedWord>();
    for (IndexedWord vertex : vertexes) {
      IndexedWord newVertex = new IndexedWord(vertex);
      addVertex(newVertex);
      prevToNewMap.put(vertex, newVertex);
    }
    roots = new HashSet<IndexedWord>();
    for (IndexedWord oldRoot : oldRoots) {
      roots.add(prevToNewMap.get(oldRoot));
    }
    Set<SemanticGraphEdge> edges = g.edgeSet();
    for (SemanticGraphEdge edge : edges) {
      IndexedWord newGov = prevToNewMap.get(edge.getGovernor());
      IndexedWord newDep = prevToNewMap.get(edge.getDependent());
      addEdge(newGov, newDep, edge.getRelation(), edge.getWeight());
    }
  }

  /**
   * Copies a the current graph, but also sets the mapping from the old to new
   * graph.
   */
  public SemanticGraph(SemanticGraph g, Map<IndexedWord, IndexedWord> prevToNewMap) {
    super(g.getEdgeFactory());
    Collection<IndexedWord> oldRoots = new ArrayList<IndexedWord>(g.getRoots());
    if (prevToNewMap == null)
      prevToNewMap = new HashMap<IndexedWord, IndexedWord>();
    Set<IndexedWord> vertexes = g.vertexSet();
    for (IndexedWord vertex : vertexes) {
      IndexedWord newVertex = new IndexedWord(vertex);
      addVertex(newVertex);
      prevToNewMap.put(vertex, newVertex);
    }
    roots = new HashSet<IndexedWord>();
    for (IndexedWord oldRoot : oldRoots) {
      roots.add(prevToNewMap.get(oldRoot));
    }
    Set<SemanticGraphEdge> edges = g.edgeSet();
    for (SemanticGraphEdge edge : edges) {
      IndexedWord newGov = prevToNewMap.get(edge.getGovernor());
      IndexedWord newDep = prevToNewMap.get(edge.getDependent());
      addEdge(newGov, newDep, edge.getRelation(), edge.getWeight());
    }
  }

  /**
   * This is the constructor used by the parser.
   */
  public SemanticGraph(Collection<TypedDependency> dependencies, Collection<TreeGraphNode> roots) {
    this(dependencies, roots, "", 0);
  }

  public SemanticGraph(Collection<TypedDependency> dependencies, Collection<TreeGraphNode> rootNodes, String docID,
      int sentIndex) {
    this(dependencies, rootNodes, docID, sentIndex, false, false);
  }

  /**
   * <i>Note:</i> This method hasn't been finished.
   *
   */
  public SemanticGraph(Collection<TypedDependency> dependencies, Collection<TreeGraphNode> rootNodes, String docID,
      int sentIndex, boolean lemmatize, boolean threadSafe) {
    super(new SemanticGraphEdge.Factory());
    for (TypedDependency d : dependencies) {
      TreeGraphNode gov = d.gov();
      TreeGraphNode dep = d.dep();
      GrammaticalRelation reln = d.reln();
      // attention: the Labels [gov|dep].label() contain the words and their
      // indices!
      // but the CoreLabels govLabel/depLabel throw away the index information
      CoreLabel govLabel = new CoreLabel(gov.label());
      CoreLabel depLabel = new CoreLabel(dep.label());

      IndexedWord govVertex = new IndexedWord(docID, sentIndex, gov.index(), govLabel);
      govVertex.setTag(gov.highestNodeWithSameHead().headTagNode().value());
      IndexedWord depVertex = new IndexedWord(docID, sentIndex, dep.index(), depLabel);
      depVertex.setTag(dep.highestNodeWithSameHead().headTagNode().value());
      if (lemmatize) {
        if (!threadSafe) {
          govVertex.setLemma(Morphology.stemStatic(govVertex.value(), govVertex.tag(), true).value());
          depVertex.setLemma(Morphology.stemStatic(depVertex.value(), depVertex.tag(), true).value());
        } else {
          govVertex.setLemma(Morphology.stemStaticSynchronized(govVertex.value(), govVertex.tag(), true).value());
          depVertex.setLemma(Morphology.stemStaticSynchronized(depVertex.value(), depVertex.tag(), true).value());
        }
      }
      addVertex(govVertex);
      addVertex(depVertex);
      addEdge(govVertex, depVertex, reln, Double.NEGATIVE_INFINITY);
    }
    roots = new HashSet<IndexedWord>();

    // there used to be an if clause that filtered out the case of empty
    // dependencies. However, I could not understand (or replicate) the error
    // it alluded to, and it led to empty dependency graphs for very short
    // fragments,
    // which meant they were ignored by the RTE system. Changed. (pado)
    // See also SemanticGraphFactory.makeGraphFromTree().

    if (rootNodes != null) {
      for (TreeGraphNode rootNode : rootNodes) {
        CoreLabel rootLabel = new CoreLabel(rootNode.label());
        IndexedWord root = new IndexedWord(docID, sentIndex, rootNode.index(), rootLabel);
        roots.add(root);
      }
    }
  }

  /**
   * Gets the shortest undirected path between two edges in the graph.
   */
  public List<SemanticGraphEdge> getShortestPathEdges(IndexedWord source, IndexedWord target) {
    assert (containsVertex(source) && containsVertex(target));
    return DijkstraShortestPathIgnoringEdgeDirection.findPathBetween(this, source, target);
  }

  /**
   * Returns the shortest directed path between two edges in the graph.
   *
   * @param source
   *          node
   * @param target
   *          node
   * @return shortest directed path from source to target
   */
  public List<SemanticGraphEdge> getShortestDirectPathEdges(IndexedWord source, IndexedWord target) {
    return DijkstraShortestPath.findPathBetween(this, source, target);
  }

  /**
   * Returns the nodes in the shortest undirected path between two edges in the
   * graph. if source == target, returns a singleton list
   *
   * @param source
   *          node
   * @param target
   *          node
   * @return nodes along shortest undirected path from source to target, in
   *         order
   */
  public List<IndexedWord> getShortestPathNodes(IndexedWord source, IndexedWord target) {
    if (source == target) {
      List<IndexedWord> path = new ArrayList<IndexedWord>();
      path.add(source);
      return path;
    }
    List<SemanticGraphEdge> edgePath = getShortestPathEdges(source, target);
    if (edgePath == null || edgePath.size() == 0)
      return null;

    List<IndexedWord> result = Generics.newArrayList(edgePath.size() + 1);
    IndexedWord lastNode = source;
    result.add(lastNode);
    for (SemanticGraphEdge edge : edgePath) {
      lastNode = (!edge.getTarget().equals(lastNode)) ? edge.getTarget() : edge.getSource();
      result.add(lastNode);
    }
    assert (result.get(result.size() - 1).equals(target));
    return result;
  }

  // ============================================================================

  private static final Pattern WORD_AND_INDEX_PATTERN = Pattern.compile("([^-]+)-([0-9]+)");

  /**
   * This nested class is a helper for valueOf(). It represents the task of
   * parsing a specific String representing a SemanticGraph.
   */
  private static class SemanticGraphParsingTask extends StringParsingTask<SemanticGraph> {

    private SemanticGraph sg;
    private Set<Integer> indexesUsed = new HashSet<Integer>();

    public SemanticGraphParsingTask(String s) {
      super(s);
    }

    @Override
    public SemanticGraph parse() {
      sg = new SemanticGraph();
      try {
        readWhiteSpace();
        if (!isLeftBracket(peek()))
          return null;
        readDep(null, null);
        return sg;
      } catch (ParserException e) {
        System.err.println("SemanticGraphParser warning: " + e.getMessage());
        return null;
      }
    }

    private void readDep(IndexedWord gov, String reln) {
      readWhiteSpace();
      if (!isLeftBracket(peek())) { // it's a leaf
        String label = readName();
        IndexedWord dep = makeVertex(label);
        sg.addVertex(dep);
        if (gov == null)
          sg.roots.add(dep);
        sg.addEdge(gov, dep, GrammaticalRelation.valueOf(reln), Double.NEGATIVE_INFINITY);
      } else {
        readLeftBracket();
        String label = readName();
        IndexedWord dep = makeVertex(label);
        sg.addVertex(dep);
        if (gov == null)
          sg.roots.add(dep);
        if (gov != null && reln != null) {
          sg.addEdge(gov, dep, GrammaticalRelation.valueOf(reln), Double.NEGATIVE_INFINITY);
        }
        readWhiteSpace();
        while (!isRightBracket(peek()) && !isEOF) {
          reln = readName();
          readColon();
          readDep(dep, reln);
          readWhiteSpace();
        }
        readRightBracket();
      }
    }

    private IndexedWord makeVertex(String word) {
      Integer index; // initialized below
      Pair<String, Integer> wordAndIndex = readWordAndIndex(word);
      if (wordAndIndex != null) {
        word = wordAndIndex.first();
        index = wordAndIndex.second();
      } else {
        index = getNextFreeIndex();
      }
      indexesUsed.add(index);
      // Note that, despite the use of indexesUsed and getNextFreeIndex(),
      // nothing is actually enforcing that no indexes are used twice. This
      // could occur if some words in the string representation being parsed
      // come with index markers and some do not.
      IndexedWord ifl = new IndexedWord(null, 0, index);
      // System.err.println("SemanticGraphParsingTask>>> word = " + word);
      // System.err.println("SemanticGraphParsingTask>>> index = " + index);
      // System.err.println("SemanticGraphParsingTask>>> indexesUsed = " +
      // indexesUsed);
      String[] wordAndTag = word.split("/");
      ifl.set(TextAnnotation.class, wordAndTag[0]);
      if (wordAndTag.length > 1)
        ifl.set(PartOfSpeechAnnotation.class, wordAndTag[1]);
      return ifl;
    }

    private static Pair<String, Integer> readWordAndIndex(String word) {
      Matcher matcher = WORD_AND_INDEX_PATTERN.matcher(word);
      if (!matcher.matches()) {
        return null;
      } else {
        word = matcher.group(1);
        Integer index = Integer.valueOf(matcher.group(2));
        return new Pair<String, Integer>(word, index);
      }
    }

    private Integer getNextFreeIndex() {
      int i = 0;
      while (indexesUsed.contains(i))
        i++;
      return i;
    }

    private void readLeftBracket() {
      // System.out.println("Read left.");
      readWhiteSpace();
      char ch = read();
      if (!isLeftBracket(ch))
        throw new ParserException("Expected left paren!");
    }

    private void readRightBracket() {
      // System.out.println("Read right.");
      readWhiteSpace();
      char ch = read();
      if (!isRightBracket(ch))
        throw new ParserException("Expected right paren!");
    }

    private void readColon() {
      readWhiteSpace();
      if (isColon(peek()))
        read();
    }

    private static boolean isLeftBracket(char ch) {
      return ch == '[';
    }

    private static boolean isRightBracket(char ch) {
      return ch == ']';
    }

    private static boolean isColon(char ch) {
      return ch == ':';
    }

    @Override
    protected boolean isPunct(char ch) {
      return isLeftBracket(ch) || isRightBracket(ch) || isColon(ch);
    }

  } // end SemanticGraphParsingTask

  // =======================================================================

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SemanticGraph))
      return false;
    SemanticGraph g = (SemanticGraph) o;
    return edgeSet().equals(g.edgeSet());
  }

  @Override
  public int hashCode() {
    return edgeSet().hashCode();
  }

  /**
   * Given a semantic graph, and a target relation, returns a list of all
   * relations (edges) matching.
   *
   */
  public List<SemanticGraphEdge> findAllRelns(GrammaticalRelation tgtRelation) {
    ArrayList<SemanticGraphEdge> relns = new ArrayList<SemanticGraphEdge>();
    for (SemanticGraphEdge edge : edgeList()) {
      GrammaticalRelation edgeRelation = edge.getRelation();
      if ((edgeRelation != null) && (edgeRelation.equals(tgtRelation))) {
        relns.add(edge);
      }
    }
    return relns;
  }

  /** returns a list of TypedDependency in the graph
   *  this method goes through all SemanticGraphEdge and converts to TypedDependency.
   */
  public Collection<TypedDependency> typedDependencies() {
    Collection<TypedDependency> dependencies = new ArrayList<TypedDependency>();
    for(SemanticGraphEdge e : this.edgeList()){
      TreeGraphNode gov = new TreeGraphNode(e.getGovernor());
      TreeGraphNode dep = new TreeGraphNode(e.getDependent());
      TypedDependency dependency = new TypedDependency(e.getRelation(), gov, dep);
      dependencies.add(dependency);
    }
    return dependencies;
  }

  public static void main(String[] args) {

    Treebank tb = new MemoryTreebank();
    Properties props = StringUtils.argsToProperties(args);
    String treeFileName = props.getProperty("treeFile");
    String sentFileName = props.getProperty("sentFile");
    String testGraph = props.getProperty("testGraph");
    if (testGraph == null) {
      testGraph = "false";
    }
    String load = props.getProperty("load");
    String save = props.getProperty("save");

    if (load != null) {
      System.err.println("Load not implemented!");
      return;
    }

    if (sentFileName == null && treeFileName == null) {
      System.err.println("Usage: java SemanticGraph [-sentFile file|-treeFile file] [-testGraph]");
      Tree t = Tree.valueOf("(ROOT (S (NP (NP (DT An) (NN attempt)) (PP (IN on) (NP (NP (NNP Andres) (NNP Pastrana) (POS 's)) (NN life)))) (VP (VBD was) (VP (VBN carried) (PP (IN out) (S (VP (VBG using) (NP (DT a) (JJ powerful) (NN bomb))))))) (. .)))");
      tb.add(t);
    } else if (treeFileName != null) {
      tb.loadPath(treeFileName);
    } else {
      String[] opts = new String[] { "-retainNPTmpSubcategories" };
      LexicalizedParser lp = new LexicalizedParser("/u/nlp/data/lexparser/englishPCFG.ser.gz");
      lp.setOptionFlags(opts);
      BufferedReader reader = null;
      try {
        reader = new BufferedReader(new FileReader(sentFileName));
      } catch (FileNotFoundException e) {
        System.err.println("Cannot find " + sentFileName);
        System.exit(1);
      }
      try {
        System.out.println("Processing sentence file " + sentFileName);
        String line;
        while ((line = reader.readLine()) != null) {
          System.out.println("Processing sentence: " + line);
          PTBTokenizer<Word> ptb = PTBTokenizer.newPTBTokenizer(new StringReader(line));
          List<Word> words = ptb.tokenize();
          lp.parse(words);
          Tree parseTree = lp.getBestParse();
          tb.add(parseTree);
        }
        reader.close();
      } catch (Exception e) {
        throw new RuntimeException("Exception reading key file " + sentFileName, e);
      }
    }

    for (Tree t : tb) {
      SemanticGraph sg = SemanticGraphFactory.makeFromTree(t, false);
      System.out.println(sg.toString());
      System.out.println(sg.toCompactString());

      if (testGraph.equals("true")) {
        SemanticGraph g1 = SemanticGraphFactory.makeFromTree(t);
        System.out.println("TEST SEMANTIC GRAPH - graph ----------------------------");
        System.out.println(g1.toString());
        System.out.println("readable ----------------------------");
        System.out.println(g1.toString("readable"));
        System.out.println("List of dependencies ----------------------------");
        System.out.println(g1.toList());
        System.out.println("xml ----------------------------");
        System.out.println(g1.toString("xml"));

        // System.out.println(" graph ----------------------------");
        // System.out.println(t.allTypedDependenciesCCProcessed(false));
      }
    }

    if (save != null) {

    }
  } // end main

  private static final long serialVersionUID = 1L;
}
