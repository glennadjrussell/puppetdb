;; ## Import utility
;;
;; This is a command-line tool for importing data into PuppetDB.  It expects
;; as input a tarball generated by the PuppetDB `export` command-line tool.

(ns com.puppetlabs.puppetdb.cli.import
  (:require [fs.core :as fs]
            [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.cheshire :as json]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]
            [com.puppetlabs.puppetdb.reports :as reports]
            [com.puppetlabs.puppetdb.utils :refer [export-root-dir]])
  (:import  [com.puppetlabs.archive TarGzReader]
            [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:use [puppetlabs.kitchensink.core :only (cli!)]
        [com.puppetlabs.puppetdb.cli.export :only [export-metadata-file-name]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]]))

(def cli-description "Import PuppetDB catalog data from a backup file")

(defn parse-metadata
  "Parses the export metadata file to determine, e.g., what versions of the
  commands should be used during import."
  [tarball]
  {:pre  [(fs/exists? tarball)]
   :post [(map? %)
          (contains? % :command-versions)]}
  (let [metadata-path (.getPath (io/file export-root-dir export-metadata-file-name))]
    (with-open [tar-reader (archive/tarball-reader tarball)]
      (when-not (archive/find-entry tar-reader metadata-path)
        (throw (IllegalStateException.
                 (format "Unable to find export metadata file '%s' in archive '%s'"
                   metadata-path
                   tarball))))
      (json/parse-string (archive/read-entry-content tar-reader) true))))

(defn submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port command-version catalog-payload]
  {:pre  [(string?  puppetdb-host)
          (integer? puppetdb-port)
          (integer? command-version)
          (string?  catalog-payload)]}
  (let [result (command/submit-command-via-http!
                 puppetdb-host puppetdb-port
                 (command-names :replace-catalog) command-version
                 catalog-payload)]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))

(defn submit-report
  "Send the given wire-format `report` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port command-version report-payload]
  {:pre  [(string?  puppetdb-host)
          (integer? puppetdb-port)
          (integer? command-version)
          (string?  report-payload)]}
  (let [payload (-> report-payload
                  json/parse-string
                  reports/sanitize-report)
        result  (command/submit-command-via-http!
                  puppetdb-host puppetdb-port
                  (command-names :store-report) command-version
                  payload)]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))

(defn submit-facts
  "Send the given wire-format `facts` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port fact-payload]
  {:pre  [(string?  puppetdb-host)
          (integer? puppetdb-port)
          (string?  fact-payload)]}
  (let [payload (-> fact-payload
                    json/parse-string)
        result  (command/submit-command-via-http!
                 puppetdb-host puppetdb-port
                 (command-names :replace-facts)
                 1
                 fact-payload)]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))

(defn process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [^TarGzReader tar-reader ^TarArchiveEntry tar-entry host port metadata]
  {:pre  [(instance? TarGzReader tar-reader)
          (instance? TarArchiveEntry tar-entry)
          (string? host)
          (integer? port)
          (map? metadata)]}
  (let [path    (.getName tar-entry)
        catalog-pattern (str "^" (.getPath (io/file export-root-dir "catalogs" ".*\\.json")) "$")
        report-pattern (str "^" (.getPath (io/file export-root-dir "reports" ".*\\.json")) "$")
        facts-pattern (str "^" (.getPath (io/file export-root-dir "facts" ".*\\.json")) "$")]
    (when (re-find (re-pattern catalog-pattern) path)
      (println (format "Importing catalog from archive entry '%s'" path))
      ;; NOTE: these submissions are async and we have no guarantee that they
      ;;   will succeed.  We might want to add something at the end of the import
      ;;   that polls puppetdb until the command queue is empty, then does a
      ;;   query to the /nodes endpoint and shows the set difference between
      ;;   the list of nodes that we submitted and the output of that query
      (submit-catalog host port
        (get-in metadata [:command-versions :replace-catalog])
        (archive/read-entry-content tar-reader)))
    (when (re-find (re-pattern report-pattern) path)
      (println (format "Importing report from archive entry '%s'" path))
      (submit-report host port
        (get-in metadata [:command-versions :store-report])
        (archive/read-entry-content tar-reader)))
    (when (re-find (re-pattern facts-pattern) path)
      (println (format "Importing facts from archive entry '%s'" path))
      (submit-facts host port
        (archive/read-entry-content tar-reader)))))

(defn- validate-cli!
  [args]
  (let [specs    [["-i" "--infile INFILE" "Path to backup file (required)"]
                  ["-H" "--host HOST" "Hostname of PuppetDB server" :default "localhost"]
                  ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)" :parse-fn #(Integer. %) :default 8080]]
        required [:infile]]
    (try+
      (cli! args specs required)
      (catch map? m
        (println (:message m))
        (case (:type m)
          :puppetlabs.kitchensink.core/cli-error (System/exit 1)
          :puppetlabs.kitchensink.core/cli-help  (System/exit 0))))))

(defn -main
  [& args]
  (let [[{:keys [infile host port]} _] (validate-cli! args)
        metadata                       (parse-metadata infile)]
;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (with-open [tar-reader (archive/tarball-reader infile)]
      (doseq [tar-entry (archive/all-entries tar-reader)]
        (process-tar-entry tar-reader tar-entry host port metadata)))))
