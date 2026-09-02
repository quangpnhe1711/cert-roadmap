-- Launch catalog seed: AWS Certified AI Practitioner (AIF-C01).
--
-- This is DATA, not code. Nothing in the schema or the application knows the
-- word "AWS"; adding Azure AI-900 later is another seed file.
--
-- Curated by hand with provenance rather than discovered at runtime: domain
-- weights drive the whole schedule, so a wrong weight is a wrong plan the
-- learner cannot detect. Human verification is cheaper and more accurate than
-- an extraction pipeline at a catalogue size of one.
--
-- Fixed UUIDs so seeding is reproducible and tests can reference them.

INSERT INTO certification (id, provider, name, slug) VALUES
  ('a1f00000-0000-4000-8000-000000000001', 'Amazon Web Services',
   'AWS Certified AI Practitioner', 'aws-certified-ai-practitioner');

INSERT INTO certification_version
  (id, certification_id, exam_code, version_label, effective_from,
   official_url, exam_guide_url, duration_minutes, question_count, passing_score, status)
VALUES
  ('a1f00000-0000-4000-8000-000000000002', 'a1f00000-0000-4000-8000-000000000001',
   'AIF-C01', '1.0', DATE '2024-08-13',
   'https://aws.amazon.com/certification/certified-ai-practitioner/',
   'https://d1.awsstatic.com/onedam/marketing-channels/website/aws/en_US/certification/approved/pdfs/docs-ai-practitioner/AWS-Certified-AI-Practitioner_Exam-Guide.pdf',
   120, 85, 700, 'PUBLISHED');

INSERT INTO exam_domain (id, certification_version_id, code, title, weight_percent, order_index) VALUES
  ('a1f00000-0000-4000-8000-000000000101', 'a1f00000-0000-4000-8000-000000000002',
   'D1', 'Fundamentals of AI and ML', 20, 1),
  ('a1f00000-0000-4000-8000-000000000102', 'a1f00000-0000-4000-8000-000000000002',
   'D2', 'Fundamentals of Generative AI', 24, 2),
  ('a1f00000-0000-4000-8000-000000000103', 'a1f00000-0000-4000-8000-000000000002',
   'D3', 'Applications of Foundation Models', 28, 3),
  ('a1f00000-0000-4000-8000-000000000104', 'a1f00000-0000-4000-8000-000000000002',
   'D4', 'Guidelines for Responsible AI', 14, 4),
  ('a1f00000-0000-4000-8000-000000000105', 'a1f00000-0000-4000-8000-000000000002',
   'D5', 'Security, Compliance, and Governance for AI Solutions', 14, 5);

INSERT INTO task_statement (id, exam_domain_id, code, title, order_index) VALUES
  ('a1f00000-0000-4000-8000-000000000201', 'a1f00000-0000-4000-8000-000000000101',
   '1.1', 'Explain basic AI concepts and terminologies', 1),
  ('a1f00000-0000-4000-8000-000000000202', 'a1f00000-0000-4000-8000-000000000101',
   '1.2', 'Identify practical use cases for AI', 2),
  ('a1f00000-0000-4000-8000-000000000203', 'a1f00000-0000-4000-8000-000000000101',
   '1.3', 'Describe the ML development lifecycle', 3),

  ('a1f00000-0000-4000-8000-000000000204', 'a1f00000-0000-4000-8000-000000000102',
   '2.1', 'Explain the basic concepts of generative AI', 1),
  ('a1f00000-0000-4000-8000-000000000205', 'a1f00000-0000-4000-8000-000000000102',
   '2.2', 'Understand the capabilities and limitations of generative AI for solving business problems', 2),
  ('a1f00000-0000-4000-8000-000000000206', 'a1f00000-0000-4000-8000-000000000102',
   '2.3', 'Describe AWS infrastructure and technologies for building generative AI applications', 3),

  ('a1f00000-0000-4000-8000-000000000207', 'a1f00000-0000-4000-8000-000000000103',
   '3.1', 'Describe design considerations for applications that use foundation models', 1),
  ('a1f00000-0000-4000-8000-000000000208', 'a1f00000-0000-4000-8000-000000000103',
   '3.2', 'Choose effective prompt engineering techniques', 2),
  ('a1f00000-0000-4000-8000-000000000209', 'a1f00000-0000-4000-8000-000000000103',
   '3.3', 'Describe the training and fine-tuning process for foundation models', 3),
  ('a1f00000-0000-4000-8000-000000000210', 'a1f00000-0000-4000-8000-000000000103',
   '3.4', 'Describe methods to evaluate foundation model performance', 4),

  ('a1f00000-0000-4000-8000-000000000211', 'a1f00000-0000-4000-8000-000000000104',
   '4.1', 'Explain the development of AI systems that are responsible', 1),
  ('a1f00000-0000-4000-8000-000000000212', 'a1f00000-0000-4000-8000-000000000104',
   '4.2', 'Recognize the importance of transparent and explainable models', 2),

  ('a1f00000-0000-4000-8000-000000000213', 'a1f00000-0000-4000-8000-000000000105',
   '5.1', 'Explain methods to secure AI systems', 1),
  ('a1f00000-0000-4000-8000-000000000214', 'a1f00000-0000-4000-8000-000000000105',
   '5.2', 'Recognize governance and compliance regulations for AI systems', 2);

-- Knowledge items flagged MUST_KNOW drive the lesson coverage validator.
INSERT INTO knowledge_item (id, task_statement_id, text, is_must_know, order_index) VALUES
  ('a1f00000-0000-4000-8000-000000000301', 'a1f00000-0000-4000-8000-000000000201',
   'Distinguish AI, machine learning and deep learning', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000302', 'a1f00000-0000-4000-8000-000000000201',
   'Define inference, training, model, algorithm, labelled data', TRUE, 2),
  ('a1f00000-0000-4000-8000-000000000303', 'a1f00000-0000-4000-8000-000000000203',
   'Recognise supervised, unsupervised and reinforcement learning', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000304', 'a1f00000-0000-4000-8000-000000000203',
   'Identify overfitting and underfitting', TRUE, 2),
  ('a1f00000-0000-4000-8000-000000000305', 'a1f00000-0000-4000-8000-000000000204',
   'Define foundation model, large language model, token and embedding', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000306', 'a1f00000-0000-4000-8000-000000000204',
   'Explain what a context window is and why it matters', TRUE, 2),
  ('a1f00000-0000-4000-8000-000000000307', 'a1f00000-0000-4000-8000-000000000207',
   'Explain retrieval augmented generation and when to prefer it over fine-tuning', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000308', 'a1f00000-0000-4000-8000-000000000207',
   'Describe the role of a vector store in a RAG application', FALSE, 2),
  ('a1f00000-0000-4000-8000-000000000309', 'a1f00000-0000-4000-8000-000000000208',
   'Compare zero-shot, few-shot and chain-of-thought prompting', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000310', 'a1f00000-0000-4000-8000-000000000208',
   'Explain temperature, top-p and top-k at a conceptual level', TRUE, 2),
  ('a1f00000-0000-4000-8000-000000000311', 'a1f00000-0000-4000-8000-000000000210',
   'Recognise common evaluation approaches including human evaluation and benchmark datasets', FALSE, 1),
  ('a1f00000-0000-4000-8000-000000000312', 'a1f00000-0000-4000-8000-000000000211',
   'Identify bias, fairness, and hallucination as responsible AI concerns', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000313', 'a1f00000-0000-4000-8000-000000000213',
   'Describe least privilege, encryption and data protection for AI workloads', TRUE, 1),
  ('a1f00000-0000-4000-8000-000000000314', 'a1f00000-0000-4000-8000-000000000214',
   'Recognise the need for auditing, traceability and regulated-industry compliance', FALSE, 1);

-- A small set of official documentation links used when a task statement has no
-- coverage in the learner's material. Runtime web search is deliberately absent.
INSERT INTO official_resource
  (id, certification_version_id, task_statement_id, title, url, resource_type) VALUES
  ('a1f00000-0000-4000-8000-000000000401', 'a1f00000-0000-4000-8000-000000000002',
   'a1f00000-0000-4000-8000-000000000206', 'Amazon Bedrock User Guide',
   'https://docs.aws.amazon.com/bedrock/latest/userguide/what-is-bedrock.html', 'DOCUMENTATION'),
  ('a1f00000-0000-4000-8000-000000000402', 'a1f00000-0000-4000-8000-000000000002',
   'a1f00000-0000-4000-8000-000000000207', 'Retrieval Augmented Generation with Amazon Bedrock',
   'https://docs.aws.amazon.com/bedrock/latest/userguide/knowledge-base.html', 'DOCUMENTATION'),
  ('a1f00000-0000-4000-8000-000000000403', 'a1f00000-0000-4000-8000-000000000002',
   'a1f00000-0000-4000-8000-000000000211', 'AWS Responsible AI',
   'https://aws.amazon.com/machine-learning/responsible-ai/', 'DOCUMENTATION'),
  ('a1f00000-0000-4000-8000-000000000404', 'a1f00000-0000-4000-8000-000000000002',
   'a1f00000-0000-4000-8000-000000000210', 'Model evaluation in Amazon Bedrock',
   'https://docs.aws.amazon.com/bedrock/latest/userguide/model-evaluation.html', 'DOCUMENTATION');

-- Provenance. Every factual field above traces to the published exam guide, and
-- the record says a human verified it.
INSERT INTO source_ref
  (id, entity_type, entity_id, field, url, doc_title, retrieved_at, method, confidence, verified_by, verified_at)
VALUES
  ('a1f00000-0000-4000-8000-000000000501', 'certification_version',
   'a1f00000-0000-4000-8000-000000000002', 'exam_metadata',
   'https://aws.amazon.com/certification/certified-ai-practitioner/',
   'AWS Certified AI Practitioner (AIF-C01) Exam Guide',
   now(), 'MANUAL', 1.00, 'catalog-curator', now()),
  ('a1f00000-0000-4000-8000-000000000502', 'certification_version',
   'a1f00000-0000-4000-8000-000000000002', 'domain_weights',
   'https://aws.amazon.com/certification/certified-ai-practitioner/',
   'AWS Certified AI Practitioner (AIF-C01) Exam Guide',
   now(), 'MANUAL', 1.00, 'catalog-curator', now());
