package interview;

import java.util.*;

public class EmployeeDirectory {

    class Employee{
        int id;
        String name;
        List<Employee> manager;
        List<Employee> directs;
    }

    class Pair<K,V>{
        K key;
        V value;
        Pair(K k, V v){
            key = k;
            value = v;
        }
        public K getKey(){return key;}
        public V getValue(){return value;}
    }


    int distanceToCEO(Employee e) {
        Queue<Pair<Employee, Integer>> q = new LinkedList<>();
        Set<Employee> visited = new HashSet<>();
        q.add(new Pair<>(e, 0));
        visited.add(e);

        while (!q.isEmpty()) {
            Pair<Employee, Integer> cur = q.poll();
            Employee emp = cur.getKey();
            int dist = cur.getValue();

            if (emp.manager.isEmpty()) {
                return dist;
            }

            for (Employee mgr : emp.manager) {
                if (visited.add(mgr)) {
                    q.add(new Pair<>(mgr, dist + 1));
                }
            }
        }
        return -1;
    }


    int distanceToIco(Employee e) {
        if(e.directs.isEmpty() && e.directs == null) return 0;
        int result = 0;
        for(Employee d : e.directs){
            result = Math.max(result, distanceToIco(d));
        }
        return result+1;
    }







}
