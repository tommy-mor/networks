(ns networks.table-test
  (:require [clojure.test :refer :all]
            [networks.table :refer :all]))

(deftest a-test
  (testing "selforigin wins"
    (is (= "172.168.0.2"
           (first (best-route (vector ["172.168.0.2"
                                                     {:network "172.169.0.0", :netmask "255.255.0.0",
                                                      :localpref 100, :ASPath [2], :origin "EGP", :selfOrigin true}]
                                                    ["10.0.0.2"
                                                     {:network "172.169.0.0", :netmask "255.255.0.0",
                                                      :localpref 100, :ASPath [3 2], :origin "EGP", :selfOrigin false}])))))



    (is (= "192.168.0.2"
           (-> (vector ["10.0.0.2"
                        {:network "12.0.0.0", :netmask "255.0.0.0",
                         :localpref 100, :ASPath [3 4], :origin "EGP", :selfOrigin false}]
                       ["192.168.0.2"
                        {:network "12.0.0.0", :netmask "255.0.0.0",
                         :localpref 150, :ASPath [1 4], :origin "EGP", :selfOrigin false}])
               best-route
               first)))))


(deftest filter-test
  (testing "filter table"

    (is (= 3 (count (filter-routing-table [["192.168.0.2" {:network "192.168.1.0", :netmask "255.255.255.0"}]
                                           
                                           ["172.168.0.2" {:network "172.169.0.0", :netmask "255.255.0.0"}]
                                           ["10.0.0.2" {:network "11.0.0.0", :netmask "255.0.0.0"}]
                                           ["10.0.0.2" {:network "172.169.0.0", :netmask "255.255.0.0"}]]
                                          {:type "withdraw", :src "172.168.0.2", :dst "172.168.0.1",
                                           :msg [{:network "172.169.0.0", :netmask "255.255.0.0"}]}))))))
