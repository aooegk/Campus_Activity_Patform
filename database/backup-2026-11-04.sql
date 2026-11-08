-- 东八区口径，2026-11-06 迁移；2026-11-08 种子：第 4 个活动（周日羽毛球友谊赛，组织者 organizer，demo01 未预报名），旧 3 个种子 +24h（截止 11-08 19:09、开始 11-09 19:09–21:09）；任务 11 种子介绍/条件/地点写具体（江安校区、水平费用进条件）；任务 3 通知 type CHECK 已放开 '退出'；任务 1 sweep 已补 READY→OPEN 回流，时间平移后状态 10 秒内自动翻回，不再需要手动复位

--
-- PostgreSQL database dump
--

-- Dumped from database version 16.2 (Ubuntu 16.2-1ubuntu4)
-- Dumped by pg_dump version 16.2 (Ubuntu 16.2-1ubuntu4)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: activity; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.activity (
    id bigint NOT NULL,
    organizer_id bigint NOT NULL,
    title character varying(50) NOT NULL,
    category character varying(10) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    conditions character varying(500) DEFAULT ''::character varying NOT NULL,
    location character varying(100) NOT NULL,
    capacity integer NOT NULL,
    deadline timestamp without time zone NOT NULL,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone NOT NULL,
    status character varying(10) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT activity_capacity_check CHECK (((capacity >= 1) AND (capacity <= 100))),
    CONSTRAINT activity_category_check CHECK (((category)::text = ANY (ARRAY[('羽毛球'::character varying)::text, ('桌游'::character varying)::text, ('学习组'::character varying)::text, ('其他'::character varying)::text]))),
    CONSTRAINT activity_check CHECK (((deadline <= start_time) AND (start_time < end_time))),
    CONSTRAINT activity_conditions_check CHECK ((char_length((conditions)::text) <= 500)),
    CONSTRAINT activity_description_check CHECK ((char_length(description) <= 2000)),
    CONSTRAINT activity_location_check CHECK (((char_length((location)::text) >= 1) AND (char_length((location)::text) <= 100))),
    CONSTRAINT activity_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('READY'::character varying)::text, ('RUNNING'::character varying)::text, ('ENDED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT activity_title_check CHECK (((char_length((title)::text) >= 1) AND (char_length((title)::text) <= 50)))
);


ALTER TABLE public.activity OWNER TO postgres;

--
-- Name: activity_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.activity_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.activity_id_seq OWNER TO postgres;

--
-- Name: activity_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.activity_id_seq OWNED BY public.activity.id;


--
-- Name: app_user; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.app_user (
    id bigint NOT NULL,
    account character varying(32) NOT NULL,
    password_hash character varying(255) NOT NULL,
    nickname character varying(30) NOT NULL,
    contact character varying(100) DEFAULT ''::character varying NOT NULL,
    role character varying(10) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(10) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT app_user_account_check CHECK (((char_length((account)::text) >= 4) AND (char_length((account)::text) <= 32))),
    CONSTRAINT app_user_nickname_check CHECK (((char_length((nickname)::text) >= 1) AND (char_length((nickname)::text) <= 30))),
    CONSTRAINT app_user_role_check CHECK (((role)::text = ANY (ARRAY[('USER'::character varying)::text, ('ADMIN'::character varying)::text]))),
    CONSTRAINT app_user_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


ALTER TABLE public.app_user OWNER TO postgres;

--
-- Name: app_user_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.app_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.app_user_id_seq OWNER TO postgres;

--
-- Name: app_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.app_user_id_seq OWNED BY public.app_user.id;


--
-- Name: feedback; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.feedback (
    id bigint NOT NULL,
    participation_id bigint NOT NULL,
    score integer NOT NULL,
    content character varying(500) DEFAULT ''::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT feedback_content_check CHECK ((char_length((content)::text) <= 500)),
    CONSTRAINT feedback_score_check CHECK (((score >= 1) AND (score <= 5)))
);


ALTER TABLE public.feedback OWNER TO postgres;

--
-- Name: feedback_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.feedback_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.feedback_id_seq OWNER TO postgres;

--
-- Name: feedback_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.feedback_id_seq OWNED BY public.feedback.id;


--
-- Name: invitation; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.invitation (
    id bigint NOT NULL,
    participation_id bigint NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    issued_at timestamp without time zone NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    responded_at timestamp without time zone,
    event_key character varying(100) NOT NULL,
    CONSTRAINT invitation_check CHECK ((issued_at < expires_at)),
    CONSTRAINT invitation_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('REJECTED'::character varying)::text, ('EXPIRED'::character varying)::text, ('INVALID'::character varying)::text])))
);


ALTER TABLE public.invitation OWNER TO postgres;

--
-- Name: invitation_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.invitation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.invitation_id_seq OWNER TO postgres;

--
-- Name: invitation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.invitation_id_seq OWNED BY public.invitation.id;


--
-- Name: notification; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.notification (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    activity_id bigint,
    invitation_id bigint,
    type character varying(10) NOT NULL,
    content text NOT NULL,
    event_key character varying(100) NOT NULL,
    read_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT notification_content_check CHECK ((char_length(content) <= 1000)),
    CONSTRAINT notification_type_check CHECK (((type)::text = ANY ((ARRAY['报名'::character varying, '候补'::character varying, '邀请'::character varying, '变更'::character varying, '退出'::character varying])::text[])))
);


ALTER TABLE public.notification OWNER TO postgres;

--
-- Name: notification_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.notification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.notification_id_seq OWNER TO postgres;

--
-- Name: notification_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.notification_id_seq OWNED BY public.notification.id;


--
-- Name: participation; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.participation (
    id bigint NOT NULL,
    activity_id bigint NOT NULL,
    user_id bigint NOT NULL,
    status character varying(10) NOT NULL,
    queued_at timestamp without time zone,
    joined_at timestamp without time zone,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT participation_status_check CHECK (((status)::text = ANY (ARRAY[('FORMAL'::character varying)::text, ('WAITING'::character varying)::text, ('INVITED'::character varying)::text, ('WITHDRAWN'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


ALTER TABLE public.participation OWNER TO postgres;

--
-- Name: participation_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.participation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.participation_id_seq OWNER TO postgres;

--
-- Name: participation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.participation_id_seq OWNED BY public.participation.id;


--
-- Name: activity id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.activity ALTER COLUMN id SET DEFAULT nextval('public.activity_id_seq'::regclass);


--
-- Name: app_user id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.app_user ALTER COLUMN id SET DEFAULT nextval('public.app_user_id_seq'::regclass);


--
-- Name: feedback id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.feedback ALTER COLUMN id SET DEFAULT nextval('public.feedback_id_seq'::regclass);


--
-- Name: invitation id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.invitation ALTER COLUMN id SET DEFAULT nextval('public.invitation_id_seq'::regclass);


--
-- Name: notification id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification ALTER COLUMN id SET DEFAULT nextval('public.notification_id_seq'::regclass);


--
-- Name: participation id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.participation ALTER COLUMN id SET DEFAULT nextval('public.participation_id_seq'::regclass);


--
-- Data for Name: activity; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.activity (id, organizer_id, title, category, description, conditions, location, capacity, deadline, start_time, end_time, status, created_at, updated_at) FROM stdin;
1	1	周六羽毛球双打	羽毛球	周六下午羽毛球双打，新手友好，欢迎一个人来；现场分组轮换，保证每人都能打上。	水平不限，免费参加；自带球拍，没有球拍可提前说一声	江安校区体育馆三楼	4	2026-11-08 19:09:12.336937	2026-11-09 19:09:12.336937	2026-11-09 21:09:12.336937	OPEN	2026-11-05 01:09:12.337632	2026-11-05 01:09:12.337632
2	1	桌游之夜	桌游	轻松桌游夜，有主持人带玩，新手友好，欢迎一个人来；狼人杀、卡坦岛、剧本杀轮着玩。	水平不限，免费参加；自带想玩的桌游也可以	江安校区活动中心201	4	2026-11-08 19:09:12.336937	2026-11-09 19:09:12.336937	2026-11-09 21:09:12.336937	OPEN	2026-11-05 01:09:12.339019	2026-11-05 01:09:12.339019
3	1	周末学习组	学习组	周末一起自习，互相监督、互不打扰；中途可以组队讨论题目，新手友好，欢迎一个人来。	水平不限，免费参加；自带学习资料，学习时保持安静	江安校区图书馆三楼	10	2026-11-08 19:09:12.336937	2026-11-09 19:09:12.336937	2026-11-09 21:09:12.336937	OPEN	2026-11-05 01:09:12.339985	2026-11-05 01:09:12.339985
4	1	周日羽毛球友谊赛	羽毛球	周日羽毛球友谊赛，以球会友、切磋为主；新手友好，欢迎一个人来，现场分组保证每人都有上场机会。	水平不限，免费参加；自带球拍和水	江安校区体育馆三楼	6	2026-11-08 19:09:12.336937	2026-11-09 19:09:12.336937	2026-11-09 21:09:12.336937	OPEN	2026-11-08 09:32:47.802226	2026-11-08 09:32:47.802226
\.


--
-- Data for Name: app_user; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.app_user (id, account, password_hash, nickname, contact, role, status, created_at) FROM stdin;
1	organizer	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	组织者小陈		USER	ACTIVE	2026-11-05 01:09:12.325273
2	demo01	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学1		USER	ACTIVE	2026-11-05 01:09:12.327632
3	demo02	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学2		USER	ACTIVE	2026-11-05 01:09:12.328293
4	demo03	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学3		USER	ACTIVE	2026-11-05 01:09:12.328739
5	demo04	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学4		USER	ACTIVE	2026-11-05 01:09:12.329671
6	demo05	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学5		USER	ACTIVE	2026-11-05 01:09:12.330362
7	demo06	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学6		USER	ACTIVE	2026-11-05 01:09:12.330802
8	demo07	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学7		USER	ACTIVE	2026-11-05 01:09:12.331101
9	demo08	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学8		USER	ACTIVE	2026-11-05 01:09:12.331483
10	demo09	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学9		USER	ACTIVE	2026-11-05 01:09:12.332001
11	demo10	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学10		USER	ACTIVE	2026-11-05 01:09:12.332413
12	demo11	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学11		USER	ACTIVE	2026-11-05 01:09:12.332954
13	demo12	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学12		USER	ACTIVE	2026-11-05 01:09:12.333534
14	demo13	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学13		USER	ACTIVE	2026-11-05 01:09:12.333854
15	demo14	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学14		USER	ACTIVE	2026-11-05 01:09:12.334135
16	demo15	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学15		USER	ACTIVE	2026-11-05 01:09:12.334438
17	demo16	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学16		USER	ACTIVE	2026-11-05 01:09:12.334826
18	demo17	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学17		USER	ACTIVE	2026-11-05 01:09:12.335233
19	demo18	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学18		USER	ACTIVE	2026-11-05 01:09:12.335624
20	demo19	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学19		USER	ACTIVE	2026-11-05 01:09:12.335907
21	demo20	3933ecbb9ed4a06e71a7ea6ce6985713:ddab816d9ced9e7f05644875ba6c25bb4aafca1fcd2e0ceaee93f50d57d11689	同学20		USER	ACTIVE	2026-11-05 01:09:12.336177
\.


--
-- Data for Name: feedback; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.feedback (id, participation_id, score, content, created_at) FROM stdin;
\.


--
-- Data for Name: invitation; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.invitation (id, participation_id, status, issued_at, expires_at, responded_at, event_key) FROM stdin;
\.


--
-- Data for Name: notification; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.notification (id, user_id, activity_id, invitation_id, type, content, event_key, read_at, created_at) FROM stdin;
\.


--
-- Data for Name: participation; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.participation (id, activity_id, user_id, status, queued_at, joined_at, updated_at) FROM stdin;
1	1	2	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
2	1	3	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
3	1	4	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
4	2	2	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
5	2	3	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
6	2	4	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
7	2	5	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
8	2	6	WAITING	2026-11-05 01:14:12.336937	\N	2026-11-05 01:09:12.336937
9	2	7	WAITING	2026-11-05 01:15:12.336937	\N	2026-11-05 01:09:12.336937
11	3	3	FORMAL	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
10	3	2	WITHDRAWN	\N	2026-11-05 01:09:12.336937	2026-11-05 01:09:12.336937
\.


--
-- Name: activity_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.activity_id_seq', 4, true);


--
-- Name: app_user_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.app_user_id_seq', 21, true);


--
-- Name: feedback_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.feedback_id_seq', 1, false);


--
-- Name: invitation_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.invitation_id_seq', 1, false);


--
-- Name: notification_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.notification_id_seq', 1, false);


--
-- Name: participation_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.participation_id_seq', 11, true);


--
-- Name: activity activity_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.activity
    ADD CONSTRAINT activity_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_account_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_account_key UNIQUE (account);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: feedback feedback_participation_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_participation_id_key UNIQUE (participation_id);


--
-- Name: feedback feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);


--
-- Name: invitation invitation_event_key_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.invitation
    ADD CONSTRAINT invitation_event_key_key UNIQUE (event_key);


--
-- Name: invitation invitation_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.invitation
    ADD CONSTRAINT invitation_pkey PRIMARY KEY (id);


--
-- Name: notification notification_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_pkey PRIMARY KEY (id);


--
-- Name: notification notification_user_id_event_key_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_user_id_event_key_key UNIQUE (user_id, event_key);


--
-- Name: participation participation_activity_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.participation
    ADD CONSTRAINT participation_activity_id_user_id_key UNIQUE (activity_id, user_id);


--
-- Name: participation participation_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.participation
    ADD CONSTRAINT participation_pkey PRIMARY KEY (id);


--
-- Name: idx_inv_status_expires; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_inv_status_expires ON public.invitation USING btree (status, expires_at);


--
-- Name: idx_notif_user_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_notif_user_created ON public.notification USING btree (user_id, created_at DESC);


--
-- Name: idx_part_activity_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_part_activity_status ON public.participation USING btree (activity_id, status, queued_at, id);


--
-- Name: activity activity_organizer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.activity
    ADD CONSTRAINT activity_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES public.app_user(id);


--
-- Name: feedback feedback_participation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_participation_id_fkey FOREIGN KEY (participation_id) REFERENCES public.participation(id);


--
-- Name: invitation invitation_participation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.invitation
    ADD CONSTRAINT invitation_participation_id_fkey FOREIGN KEY (participation_id) REFERENCES public.participation(id);


--
-- Name: notification notification_activity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_activity_id_fkey FOREIGN KEY (activity_id) REFERENCES public.activity(id);


--
-- Name: notification notification_invitation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_invitation_id_fkey FOREIGN KEY (invitation_id) REFERENCES public.invitation(id);


--
-- Name: notification notification_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id);


--
-- Name: participation participation_activity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.participation
    ADD CONSTRAINT participation_activity_id_fkey FOREIGN KEY (activity_id) REFERENCES public.activity(id);


--
-- Name: participation participation_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.participation
    ADD CONSTRAINT participation_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id);


--
-- PostgreSQL database dump complete
--

