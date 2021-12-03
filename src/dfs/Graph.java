package dfs;

import java.util.Iterator;
import java.util.LinkedList;

public class Graph {
    private int numberOfVertices;
    private LinkedList<Integer> adjList[];
    private boolean visited[];
    Graph(int vertices){
        this.numberOfVertices = vertices;
        this.adjList = new LinkedList[vertices];
        this.visited = new boolean[vertices];
        for (int i = 0; i < vertices; i++)
            adjList[i] = new LinkedList<Integer>();
    }

    void addEdge(int source, int destination){
        this.adjList[source].add(destination);
    }

    void DFS(int vertex){
        visited[vertex] = true;
        System.out.println("visited "+ vertex);
        Iterator iterator = adjList[vertex].listIterator();
        while(iterator.hasNext()){
            int adjNode = (int) iterator.next();
            if(!visited[adjNode])
                DFS(adjNode);
        }
    }

    public static void main(String[] args) {
        Graph graph = new Graph(4);
        graph.addEdge(0, 1);
        graph.addEdge(0, 2);
        graph.addEdge(1, 2);
        graph.addEdge(2, 3);

        System.out.println("Following is Depth First Traversal");

        graph.DFS(2);
    }
}
