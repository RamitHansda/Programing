package bfs;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

public class Graph {
    int vertices;
    LinkedList<Integer> adjList[];

    Graph(int vertices){
        this.vertices = vertices;
        adjList= new LinkedList[vertices];
        for (int i=0;i<vertices;i++){
            adjList[i]= new LinkedList<>();
        }
    }
    void addEdge(int source, int destination){
        adjList[source].add(destination);
    }

    void BFS(int vertex){
        boolean visited[] = new boolean[vertices];
        Queue<Integer> queue = new LinkedList<>();
        visited[vertex] = true;
        queue.add(vertex);
        while (!queue.isEmpty()){
            vertex = queue.poll();
            System.out.println("Visited "+vertex);
            Iterator iterator = adjList[vertex].listIterator();
            while (iterator.hasNext()){
                int n = (int) iterator.next();
                if(!visited[n]){
                    visited[n] = true;
                    queue.add(n);
                }
            }
        }
    }
    public static void main(String[] args) {
        Graph graph = new Graph(4);
        graph.addEdge(0, 1);
        graph.addEdge(0, 2);
        graph.addEdge(1, 2);
        graph.addEdge(2, 3);

        System.out.println("Following is Depth First Traversal");

        graph.BFS(0);
    }
}
