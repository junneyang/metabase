(ns metabase.api.native-query-snippet-test
  "Tests for /api/native-query-snippet endpoints."
  (:require [clojure
             [string :as str]
             [test :refer :all]]
            [metabase.models.native-query-snippet :refer [NativeQuerySnippet]]
            [metabase.test :as mt]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]))

(def ^:private test-snippet-fields [:content :creator_id :description :name])

(defn- snippet-url
  [& [arg & _]]
  (str "native-query-snippet"
       (when arg (str "/" arg))))

(defn- name-schema-error?
  [response]
  (str/starts-with? (or (get-in response [:errors :name]) "")
                    "Value does not match schema: "))

(deftest list-snippets-api-test
  (testing "GET /api/native-query-snippet"
    (mt/with-temp* [NativeQuerySnippet [snippet-1 {:content     "1"
                                                   :creator_id  (mt/user->id :rasta)
                                                   :description "Test snippet 1"
                                                   :name        "snippet_1"}]
                    NativeQuerySnippet [snippet-2 {:content     "2"
                                                   :creator_id  (mt/user->id :rasta)
                                                   :description "Test snippet 2"
                                                   :name        "snippet_2"}]]
      (testing "list returns all snippets. Should work for all users"
        (doseq [test-user [:crowberto :rasta]]
          (testing (format "test user = %s" test-user)
            (let [snippets-from-api (->> ((mt/user->client test-user) :get 200 (snippet-url))
                                         (map #(select-keys % test-snippet-fields))
                                         set)]
              (is (contains? snippets-from-api (select-keys snippet-1 test-snippet-fields)))
              (is (contains? snippets-from-api (select-keys snippet-2 test-snippet-fields))))))))))

(deftest read-snippet-api-test
  (testing "GET /api/native-query-snippet/:id"
    (mt/with-temp NativeQuerySnippet [snippet {:content     "-- SQL comment here"
                                               :creator_id  (mt/user->id :rasta)
                                               :description "SQL comment snippet"
                                               :name        "comment"}]
      (testing "all users should be able to see all snippets in CE"
        (doseq [test-user [:crowberto :rasta]]
          (testing (format "with test user %s" test-user)
            (let [snippet-from-api ((mt/user->client test-user) :get 200 (snippet-url (:id snippet)))]
              (is (= (select-keys snippet test-snippet-fields)
                     (select-keys snippet-from-api test-snippet-fields))))))))))

(deftest create-snippet-api-test
  (testing "POST /api/native-query-snippet"
    (testing "new snippet field validation"
      (is (= {:errors {:content "value must be a string."}}
             ((mt/user->client :rasta) :post 400 (snippet-url) {})))

      (is (name-schema-error? ((mt/user->client :rasta)
                               :post 400 (snippet-url)
                               {:content "NULL"})))

      (is (name-schema-error? ((mt/user->client :rasta) :post 400 (snippet-url)
                               {:content "NULL"
                                :name    " starts with a space"})))

      (is (name-schema-error? ((mt/user->client :rasta) :post 400 (snippet-url)
                               {:content "NULL"
                                :name    "contains a } character"})))))

  (testing "successful create returns new snippet's data"
    (try
      (let [snippet-input    {:name "test-snippet", :description "Just null", :content "NULL"}
            snippet-from-api ((mt/user->client :crowberto) :post 200 (snippet-url) snippet-input)]
        (is (schema= {:id          su/IntGreaterThanZero
                      :name        (s/eq "test-snippet")
                      :description (s/eq "Just null")
                      :content     (s/eq "NULL")
                      :creator_id  (s/eq (mt/user->id :crowberto))
                      :archived    (s/eq false)
                      :created_at  java.time.OffsetDateTime
                      :updated_at  java.time.OffsetDateTime
                      s/Keyword    s/Any}
                     snippet-from-api)))
      (finally
        (db/delete! NativeQuerySnippet :name "test-snippet"))))

  (testing "create fails for non-admin user"
    (is (= "You don't have permissions to do that."
           ((mt/user->client :rasta)
            :post 403 (snippet-url)
            {:name "test-snippet", :content "NULL"})))))

(deftest update-snippet-api-test
  (testing "PUT /api/native-query-snippet/:id"
    (mt/with-temp NativeQuerySnippet [snippet {:content     "-- SQL comment here"
                                               :creator_id  (mt/user->id :rasta)
                                               :description "SQL comment snippet"
                                               :name        "comment"}]
      (testing "update stores updated snippet"
        (let [updated-desc    "Updated description."
              updated-snippet ((mt/user->client :crowberto)
                               :put 200 (snippet-url (:id snippet))
                               {:description updated-desc})]
          (is (= updated-desc (:description updated-snippet)))))

      (testing "update fails for non-admin user"
        (is (= "You don't have permissions to do that."
               ((mt/user->client :rasta)
                :put 403 (snippet-url (:id snippet))
                {:description "This description shouldn't get updated due to permissions error."})))))))
